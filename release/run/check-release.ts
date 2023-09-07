import 'dotenv/config';
import { Octokit } from "@octokit/rest";

import { getEnterpriseVersion, getOSSVersion, isValidVersionString } from '../src/version-helpers';
import { checkRelease } from "../src/check-release";

const github = new Octokit({ auth: process.env.GITHUB_TOKEN });
const version = process.argv[2];

if (!isValidVersionString(version)) {
  throw new Error("Invalid Version");
}

const owner = "iethree"; // FIXME
const repo = "metabase"; // FIXME

console.log(await checkRelease({ version: getOSSVersion(version), github, owner, repo }));
console.log(await checkRelease({ version: getEnterpriseVersion(version), github, owner, repo }));
