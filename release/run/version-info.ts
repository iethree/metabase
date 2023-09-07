import 'dotenv/config';
import { Octokit } from "@octokit/rest";
const version = process.argv[2];

import { getOSSVersion, getEnterpriseVersion, isValidVersionString } from '../src/version-helpers';
import { getVersionInfo } from "../src/version-info";

if (!isValidVersionString(version)) {
  throw new Error("Invalid Version");
}

const github = new Octokit({ auth: process.env.GITHUB_TOKEN });

const owner = "metabase"; // FIXME
const repo = "metabase"; // FIXME

console.log(await getVersionInfo({ version: getOSSVersion(version), github, owner, repo }));
console.log(await getVersionInfo({ version: getEnterpriseVersion(version), github, owner, repo }));
