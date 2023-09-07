
import { isRCVersion, isEnterpriseVersion, getOSSVersion, getEnterpriseVersion } from "./version-helpers";

import type { ReleaseProps } from "./types";
import type { Octokit } from "@octokit/rest";

const findMilestone = async ({ version, github, owner, repo}: ReleaseProps) => {
  const milestones = await github.rest.issues.listMilestones({
    owner,
    repo,
    state: "open",
  });

  const expectedMilestoneName = version.replace(/v\d/, "0").replace(/\.0$/, "");

  return milestones.data.find(
    (milestone) => milestone.title === expectedMilestoneName,
  )?.number;
};

export const getMilestoneIssues = async ({ version, github, owner, repo}: ReleaseProps) => {
  const milestoneId = await findMilestone({ version, github, owner, repo });

  if (!milestoneId) {
    return [];
  }

  const milestone = await github.rest.issues.listForRepo({
    owner,
    repo,
    milestone: milestoneId.toString(),
    state: "closed",
  });

  return milestone?.data ?? [];
};

// for the purposes of github releases, RCs and enterprise versions are never latest
const isNeverLatest = (version: string) =>
  isRCVersion(version) || isEnterpriseVersion(version);

// latest for the purposes of github release notes
export const isLatestRelease = async ({
  version,
  github,
  owner,
  repo,
}: ReleaseProps): Promise<"true" | "false"> => {
  if (isNeverLatest(version)) {
    return "false";
  }

  const releases = await github.rest.repos.listReleases({
    owner,
    repo,
  });

  const sortedReleases = releases.data
    .map((r: { tag_name: string }) => r.tag_name)
    .filter((releaseTag: string) => !isNeverLatest(releaseTag))
    .sort()
    .reverse();

  return sortedReleases[0] < version ? "true" : "false";
};

export const getChecksForRef = async ({
  github, repo, owner, ref,
}: { github: Octokit, repo: string, owner: string, ref: string }) => {
  const response = await github.rest.checks.listForRef({
    owner,
    repo,
    ref,
  });

  if (!response?.data?.check_runs) {
    return { failedChecks: [{ name: 'no checks found' }], successfulChecks: []};
  }

  const failedChecks = response.data.check_runs.filter(
    (check) => check.conclusion !== "success" && check.conclusion !== "skipped",
  );

  const successfulChecks = response.data.check_runs.filter(
    (check) => check.conclusion === "success",
  );

  return {
    failedChecks,
    successfulChecks,
  }
};

export const tagRelease = async ({
  github, version, commitHash, owner, repo,
}: ReleaseProps & { commitHash: string }) => {

  // delete old refs
  await github.rest.git.deleteRef({
    owner,
    repo,
    ref: `refs/tags/${getOSSVersion(version)}`,
  });

  await github.rest.git.deleteRef({
    owner,
    repo,
    ref: `refs/tags/${getEnterpriseVersion(version)}`,
  });

  // create new refs
  const OSSref = await github.rest.git.createRef({
    owner,
    repo,
    ref: `refs/tags/${getOSSVersion(version)}`,
    sha: commitHash,
  });

  const EEref = await github.rest.git.createRef({
    owner,
    repo,
    ref: `refs/tags/${getEnterpriseVersion(version)}`,
    sha: commitHash,
  });

  if (OSSref.status !== 201) {
    throw new Error(`failed to tag OSS release`);
  }

  if (EEref.status !== 201) {
    throw new Error(`failed to tag EE release`);
  }
};
