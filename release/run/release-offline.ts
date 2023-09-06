import "dotenv/config";
import "zx/globals";
// $.verbose = false;
import { Octokit } from "@octokit/rest";

const {
  GITHUB_TOKEN,
  GITHUB_OWNER,
  GITHUB_REPO,
} = process.env;

const github = new Octokit({ auth: GITHUB_TOKEN });
const version = process.argv?.[2]?.trim();
const commit = process.argv?.[3]?.trim();

// check environment variables
if (!GITHUB_TOKEN || !GITHUB_OWNER || !GITHUB_REPO) {
  throw new Error('You must provide Gall environment variables in .env-template');
}

if (!fs.existsSync('./target/uberjar/metabase.jar')) {
  throw new Error('You must build the jar before running this script');
}


// build jar

// test jar

// tag releases

// upload jar to s3

// containerize and upload docker image

// publish release notes

// publish new version-info.json
