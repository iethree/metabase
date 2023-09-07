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

## Automated Release with hand-holding

1. Run `yarn release`, and have a nice little javascript wizard guide you through the steps above 👆

## Local Release

1. Run `yarn release-local`, and have a nice little javascript wizard guide you through the steps above 👆
