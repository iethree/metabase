# Metabase Release

There are 3 possible paths here

- Automated Release
- Automated Release with hand-holding
- Local Automated Release

## Automated Release

1. Find the commit hash that you want to release
2. Run the "build-for-release" action on github actions, inputting the version and commit hash
3. Wait for the build and test jobs to finish
4. If everything is successful, run the "release" action on github actions, inputting the version and commit hash again

## Github action-less release

1. Run `yarn build-offline`, to prepare and build the release artifact
2. Run any tests you like on the local release artifact
3. Run `yarn release-offline`, to publish the release artifact to github

