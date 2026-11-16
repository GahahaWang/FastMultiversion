// semantic-release, run by CI (.github/workflows/build.yml). Two channels:
//
//   master  The release x.y.z, after dev was merged into master (a commit message asked for it with "merge to
//           master"). Writes the version to gradle.properties and the notes to CHANGELOG.md, commits them to
//           master, and CI merges that commit back into dev.
//   dev     A pre-release of the next version, x.y.z-dev.n, for every other push to dev. Commits nothing: the
//           tag and the GitHub pre-release carry it, and gradle.properties keeps the last release's version.
//
// Either one only happens if the Conventional Commits since the last release call for it: feat: → minor,
// fix: or perf: → patch, a breaking change (! or BREAKING CHANGE:) → major; ci:, docs:, chore:, refactor:,
// test: and build: release nothing.
const { execSync } = require('node:child_process');

const branch = process.env.GITHUB_REF_NAME || execSync('git rev-parse --abbrev-ref HEAD').toString().trim();
const release = branch === 'master';

module.exports = {
  branches: ['master', { name: 'dev', prerelease: 'dev' }],
  plugins: [
    [
      '@semantic-release/commit-analyzer',
      {
        preset: 'conventionalcommits',
        releaseRules: [
          { breaking: true, release: 'major' },
          { type: 'feat', release: 'minor' },
          { type: 'fix', release: 'patch' },
          { type: 'perf', release: 'patch' },
        ],
      },
    ],
    [
      '@semantic-release/release-notes-generator',
      {
        preset: 'conventionalcommits',
        presetConfig: {
          types: [
            { type: 'feat', section: '✨ New Features', hidden: false },
            { type: 'perf', section: '🔧 Improvements', hidden: false },
            { type: 'fix', section: '🐛 Bug Fixes', hidden: false },
          ],
        },
      },
    ],
    ...(release ? [['@semantic-release/changelog', { changelogFile: 'CHANGELOG.md' }]] : []),
    // Writes the version to gradle.properties, for the build below (and, for a release, the commit).
    'gradle-semantic-release-plugin',
    ['@semantic-release/exec', { prepareCmd: 'sh .github/scripts/package-release.sh ${nextRelease.version}' }],
    ...(release
      ? [[
          '@semantic-release/git',
          {
            assets: ['CHANGELOG.md', 'gradle.properties'],
            message: 'chore: Release v${nextRelease.version} [skip ci]\n\n${nextRelease.notes}',
          },
        ]]
      : []),
    [
      '@semantic-release/github',
      {
        assets: [{ path: 'build/release/*.zip' }],
        successComment: false,
        failComment: false,
        releasedLabels: false,
      },
    ],
  ],
};
