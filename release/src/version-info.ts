import { getMilestoneIssues } from "./github";
import { getVersionType, isEnterpriseVersion, isRCVersion } from "./version-helpers";
import type { ReleaseProps } from "./types";
import { static_bucket } from "./constants";

const generateVersionInfo = async ({ version, github, owner, repo }: ReleaseProps) => {
  const milestoneIssues = await getMilestoneIssues({ version, github, owner, repo });

  return {
    version,
    released: new Date().toISOString().slice(0, 10),
    patch: ["patch", "minor"].includes(getVersionType(version)),
    highlights: milestoneIssues.map?.((issue) => issue.title) ?? [],
  };
};

// this "latest" differs from the latest in the github releases page
const isLatestRelease = ({ newVersion, oldVersion }: { newVersion: string, oldVersion: string }) => {
  if (isRCVersion(newVersion)) {
    return "false";
  }

  return oldVersion < newVersion;
};

export const getVersionInfoUrl = (version: string) => {
  return isEnterpriseVersion(version)
    ? `http://${static_bucket}/version-info-ee.json`
    : `http://${static_bucket}/version-info.json`;
}

export async function getVersionInfo({ version, github, owner, repo }: ReleaseProps) {
  const newVersionInfo = await generateVersionInfo({ version, github, owner, repo });

  const url = getVersionInfoUrl(version);
  const existingFile = await fetch(url).then((r) => r.json());

  const newVersionJson = {
    latest: isLatestRelease({
      newVersion: version,
      oldVersion: existingFile.latest.version,
    })
      ? newVersionInfo
      : existingFile.latest,
    older: [newVersionInfo, ...existingFile.older],
  };

  return newVersionJson;
}
