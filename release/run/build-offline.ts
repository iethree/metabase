/* eslint-disable no-console */
import "dotenv/config";
import { Octokit } from "@octokit/rest";
import "zx/globals";
import { $ } from "zx";

import  {
  isValidVersionString,
  hasBeenReleased,
  isValidCommitHash,
  isEnterpriseVersion
} from '../src/index';

const {
  GITHUB_TOKEN,
  GITHUB_OWNER,
  GITHUB_REPO,
} = process.env;

const github = new Octokit({ auth: GITHUB_TOKEN });

const version = process.argv?.[2]?.trim();
const commit = process.argv?.[3]?.trim();

if (!isValidVersionString(version)) {
  throw new Error('You must provide a valid version string as the first argument (e.g v0.45.6');
}

if (!isValidCommitHash) {
  throw new Error('You must provide a valid commit hash as the second argument');
}

// check environment variables
if (!GITHUB_TOKEN || !GITHUB_OWNER || !GITHUB_REPO) {
  throw new Error('You must provide Gall environment variables in .env-template');
}

const hasThisVersionBeenReleased = await hasBeenReleased({
  github,
  owner: GITHUB_OWNER,
  repo: GITHUB_REPO,
  version,
});

if (hasThisVersionBeenReleased) {
  throw new Error(`Version ${version} has already been released`);
}

await $`git fetch --all`;
await $`git checkout ${commit}`;

// build jar
const edition = isEnterpriseVersion(version) ? 'ee' : 'oss';

await $`./bin/build.sh :edition ${edition} :version ${version}`;

console.log(`Built ${edition} jar for ${version}`);
