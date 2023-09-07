import 'dotenv/config';
import { Octokit } from "@octokit/rest";

import { getEnterpriseVersion, getOSSVersion, isValidVersionString } from '../src/version-helpers';
import { publishRelease } from "../src/release-notes";

const github = new Octokit({ auth: process.env.GITHUB_TOKEN });
const version = process.argv[2];

if (!isValidVersionString(version)) {
  throw new Error("Invalid Version");
}

const owner = "iethree"; // FIXME
const repo = "metabase"; // FIXME

console.log(await publishRelease({ version: getOSSVersion(version), github, checksum: "abc123", owner, repo }));
console.log(await publishRelease({ version: getEnterpriseVersion(version), github, checksum: "abc123", owner, repo }));
