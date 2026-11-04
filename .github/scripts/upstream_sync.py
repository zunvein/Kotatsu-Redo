#!/usr/bin/env python3
"""Helpers for the automatic upstream sync workflow (.github/workflows/sync-upstream.yml).

Three jobs, all driven from the workflow:

  resolve      finish a `git merge` that stopped on conflicts, but only for the files whose
               conflicts are caused by this fork's own deliberate edits. Anything else is
               reported and the workflow stops without publishing anything.
  room-guard   collapse the auto migrations of a Room database that this merge introduces,
               because this repository (like upstream) does not track the schema JSON files
               that KSP needs to generate them. Refuses to touch a database that already
               existed, since that one would need a real migration.
  bump         patch bump of `forkVersion` in app/build.gradle.

Deliberately conservative: it never guesses on a file it does not know, and it refuses to
leave conflict markers behind.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

CONFLICT = re.compile(
	r'^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n',
	re.S | re.M,
)

BUILD_GRADLE = 'app/build.gradle'

# Whole-hunk rules for files whose only conflicts are the fork's own edits.
HUNK_RULES = {
	# This fork drops the Discord and Weblate links; upstream keeps adding entries in the
	# same block, so every sync collides here. Our side is the one that compiles.
	'app/src/main/res/xml/pref_about.xml': 'ours',
	'app/src/main/kotlin/org/koitharu/kotatsu/settings/about/AboutSettingsFragment.kt': 'ours',
	# Version pins are upstream's business - taking ours would freeze the parser build.
	'gradle/libs.versions.toml': 'theirs',
}

# app/build.gradle conflicts line by line: the fork's version scheme is what must survive,
# everything else in that file belongs to upstream (new dependencies, plugin config, ...).
OURS_MARKERS = ('versionCode', 'versionName', 'forkVersion')

PARSERS_COORDINATE = re.compile(r'implementation\("([^"$]*:kotatsu-parsers-redo):')
PARSERS_LOCAL = 'com.github.zunvein:kotatsu-parsers-redo'

ROOM_COMMENT = (
	'// Fork: this repository keeps no Room schema JSON files, so KSP cannot generate auto\n'
	'// migrations on a clean clone. This database is introduced by this very sync, so nothing\n'
	'// can be migrating from it yet. Give it a real migration when the schema changes again.\n'
)


def git(*args: str, check: bool = True) -> str:
	result = subprocess.run(
		['git', *args],
		capture_output=True,
		text=True,
		check=False,
	)
	if check and result.returncode != 0:
		print(f"git {' '.join(args)} failed:\n{result.stderr}", file=sys.stderr)
		raise SystemExit(1)
	return result.stdout


def write_step_summary(title: str, lines: list[str]) -> None:
	"""Surface the result where the owner reads it: the run's summary page."""
	target = os.environ.get('GITHUB_STEP_SUMMARY')
	if not target:
		return
	try:
		with open(target, 'a', encoding='utf-8') as handle:
			handle.write(f'\n### {title}\n\n')
			for line in lines:
				handle.write(f'- `{line}`\n')
	except OSError:
		pass


def conflicted_files() -> list[str]:
	out = git('diff', '--name-only', '--diff-filter=U').strip()
	return [line for line in out.splitlines() if line]


def resolve_hunk(rule: str, ours: str, theirs: str) -> str:
	if rule == 'ours':
		return ours
	if rule == 'theirs':
		return theirs
	# 'build-gradle'
	return ours if any(marker in ours for marker in OURS_MARKERS) else theirs


def normalize_parsers(repo_ref: str | None) -> str | None:
	"""Follow upstream's parser coordinates.

	This fork used to depend on its own parser build; upstream has since moved the artifact, so
	the coordinate has to follow theirs. Miss this and the merge yields a combination that does
	not exist on JitPack.
	"""
	if not repo_ref:
		return None

	upstream_text = git('show', f'{repo_ref}:{BUILD_GRADLE}', check=False)
	match = PARSERS_COORDINATE.search(upstream_text)
	if match is None:
		print('resolve: upstream build.gradle has no parsers coordinate - left as it is')
		return None

	coordinate = match.group(1)
	target = Path(BUILD_GRADLE)
	text = target.read_text(encoding='utf-8')
	if PARSERS_LOCAL not in text or PARSERS_LOCAL == coordinate:
		return None

	target.write_text(text.replace(PARSERS_LOCAL, coordinate), encoding='utf-8')
	git('add', BUILD_GRADLE)
	print(f'resolve: parsers coordinate {PARSERS_LOCAL} -> {coordinate}')
	return coordinate


def cmd_resolve(args: argparse.Namespace) -> int:
	files = conflicted_files()
	if not files:
		print('resolve: nothing to do, no conflicted files')
		return 0

	handled, unknown = [], []
	for path in files:
		rule = HUNK_RULES.get(path)
		if rule is None and path == BUILD_GRADLE:
			rule = 'build-gradle'
		if rule is None:
			unknown.append(path)
			continue

		target = Path(path)
		text = target.read_text(encoding='utf-8')
		patched = CONFLICT.sub(lambda m: resolve_hunk(rule, m.group(1), m.group(2)), text)
		if '<<<<<<<' in patched or '>>>>>>>' in patched:
			# Nested or malformed block: leave it to a human.
			unknown.append(path)
			continue
		target.write_text(patched, encoding='utf-8')
		git('add', path)
		handled.append(f'{path} ({rule})')

	if handled:
		print('resolve: resolved automatically')
		for line in handled:
			print(f'  - {line}')

	if unknown:
		print('\nresolve: CONFLICTS THAT NEED A HUMAN - nothing was published', file=sys.stderr)
		for path in unknown:
			print(f'  - {path}', file=sys.stderr)
		write_step_summary('Conflicts that need a human', unknown)
		return 2

	normalize_parsers(args.upstream_tag)
	return 0


def collapse_auto_migrations(text: str) -> str:
	"""Turn `@Database(..., version = N, autoMigrations = [...])` into `version = 1`."""
	match = re.search(r'@Database\(.*?\n\)', text, re.S)
	if match is None:
		raise SystemExit('room-guard: could not locate the @Database block')

	block = match.group(0)
	start = block.find('autoMigrations')
	if start >= 0:
		brackets = block.find('[', start)
		depth = 0
		end = len(block)
		for i in range(brackets, len(block)):
			if block[i] == '[':
				depth += 1
			elif block[i] == ']':
				depth -= 1
				if depth == 0:
					end = i + 1
					break
		# Swallow the trailing comma and the whole line the entry lived on.
		line_start = block.rfind('\n', 0, start) + 1
		while end < len(block) and block[end] in ' \t,':
			end += 1
		if end < len(block) and block[end] == '\n':
			end += 1
		block = block[:line_start] + block[end:]

	block = re.sub(r'\bversion\s*=\s*\d+', 'version = 1', block, count=1)
	return text[: match.start()] + ROOM_COMMENT + block + text[match.end() :]


def cmd_room_guard(args: argparse.Namespace) -> int:
	base = args.base
	files = [
		line
		for line in git('grep', '-lF', '@Database(', '--', '*.kt').splitlines()
		if line.strip()
	]
	touched = []
	for path in files:
		target = Path(path)
		text = target.read_text(encoding='utf-8')
		if 'autoMigrations' not in text:
			continue

		existed = subprocess.run(
			['git', 'cat-file', '-e', f'{base}:{path}'],
			capture_output=True,
		).returncode == 0
		if existed:
			print(
				f'room-guard: {path} already existed before this sync and now has auto migrations.\n'
				'That needs a hand written migration plus a schema export - stopping.',
				file=sys.stderr,
			)
			return 3

		target.write_text(collapse_auto_migrations(text), encoding='utf-8')
		touched.append(path)
		print(f'room-guard: collapsed auto migrations in {path}')

	if not touched:
		print('room-guard: nothing to do')
	return 0


def cmd_bump(_args: argparse.Namespace) -> int:
	target = Path(BUILD_GRADLE)
	text = target.read_text(encoding='utf-8')
	pattern = re.compile(r"(?m)^def forkVersion = '(\d+)\.(\d+)\.(\d+)'$")
	match = pattern.search(text)
	if match is None:
		print('bump: forkVersion line not found (must be a plain quoted semver)', file=sys.stderr)
		return 4

	major, minor, patch = (int(part) for part in match.groups())
	new_version = f'{major}.{minor}.{patch + 1}'
	target.write_text(
		text[: match.start()] + f"def forkVersion = '{new_version}'" + text[match.end() :],
		encoding='utf-8',
	)
	print(new_version)
	return 0


def cmd_notes(args: argparse.Namespace) -> int:
	"""Release body for an automatically synced pre-release."""
	notes = ''
	if args.notes_file:
		try:
			notes = Path(args.notes_file).read_text(encoding='utf-8').strip()
		except OSError:
			notes = ''
	if len(notes) > 8000:
		notes = notes[:8000].rsplit('\n', 1)[0] + '\n\n_(truncated)_'

	print(f'Automated build: upstream **{args.tag}** merged into this fork, compiled, not tested by hand.')
	print()
	print('Install it if you want to try the new upstream work early. The stable channel stays')
	print('untouched until this pre-release is promoted by hand.')
	print()
	print('## What upstream shipped')
	print()
	print(notes or '_Upstream published no notes for this release._')
	print()
	print('## This fork')
	print()
	print('Batch Fix in Favourites, the fix report screen and the update check against this repo')
	print('stay as they are - see RELEASE_NOTES.md.')
	print()
	print('---')
	print()
	print('**Which APK?** `arm64-v8a` for modern phones, `armeabi-v7a` for older devices,')
	print('`universal` works everywhere. In-app updates always take the universal APK.')
	return 0


def main() -> int:
	parser = argparse.ArgumentParser(description=__doc__)
	sub = parser.add_subparsers(dest='command', required=True)

	resolve_cmd = sub.add_parser('resolve', help='resolve the known upstream sync conflicts')
	resolve_cmd.add_argument(
		'--upstream-tag',
		default=None,
		help='upstream ref whose build.gradle points at the parser artifact to use',
	)
	room = sub.add_parser('room-guard', help='collapse auto migrations of newly added databases')
	room.add_argument('--base', required=True, help='commit the merge started from')
	sub.add_parser('bump', help='patch bump forkVersion')
	notes = sub.add_parser('notes', help='print the body of the automatic pre-release')
	notes.add_argument('--version', required=True)
	notes.add_argument('--tag', required=True)
	notes.add_argument('--notes-file', default=None)

	args = parser.parse_args()
	if args.command == 'resolve':
		return cmd_resolve(args)
	if args.command == 'room-guard':
		return cmd_room_guard(args)
	if args.command == 'bump':
		return cmd_bump(args)
	if args.command == 'notes':
		return cmd_notes(args)
	parser.error('unknown command')
	return 1


if __name__ == '__main__':
	raise SystemExit(main())