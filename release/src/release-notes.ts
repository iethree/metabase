import { getMilestoneIssues, isLatestRelease } from "./github";
import { isEnterpriseVersion } from "./version-helpers";
import type { ReleaseProps } from "./types";
import { downloads_bucket } from "./constants";
import type { OctokitResponse } from "@octokit/plugin-paginate-rest/dist-types/types";

const releaseTemplate = `# NOTE: clean up 'Enhancements' and 'Bug fixes' sections and remove this line before publishing!
**Enhancements**

{{enhancements}}

**Bug fixes**

{{bug-fixes}}

**Upgrading**

You can download a .jar of the release, or get the latest on Docker. Make sure to back up your Metabase
database before you upgrade! Need help? Check out our
[upgrading instructions](https://metabase.com/docs/latest/operations-guide/upgrading-metabase.html).

Docker image: {{docker-tag}}
Download the JAR here: {{download-url}}

**Notes**

SHA-256 checksum for the {{version}} JAR:

\`\`\`
{{checksum}}
\`\`\`
`;

const isBugIssue = (issue: any) =>
  issue.labels.some((tag: any) => tag.name === "Type:Bug");
const formatIssue = (issue: any) => `- ${issue.title} (#${issue.number})`;

export const getDockerTag = (version: string) => {
  return `[\`metabase/metabase${
    isEnterpriseVersion(version) ? "-enterprise" : ""
  }:${version}\`](https://hub.docker.com/r/metabase/metabase${
    isEnterpriseVersion(version) ? "-enterprise" : ""
  }/tags)`;
};

export const getDownloadUrl = (version: string) => {
  return `https://${downloads_bucket}/${
    isEnterpriseVersion(version) ? "enterprise/" : ""
  }${version}/metabase.jar`;
};

export const getReleaseTitle = (version: string) => {
  if (isEnterpriseVersion(version)) {
    return `Metabase® Enterprise Edition™ ${version}`;
  }

  return `Metabase ${version}`;
};

const generateReleaseNotes = async ({
  version,
  github,
  repo,
  owner,
  checksum,
}: ReleaseProps & { checksum: string }) => {
  const milestoneIssues = await getMilestoneIssues({ version, github, owner, repo });

  const bugFixes = milestoneIssues.filter(isBugIssue);
  const enhancements = milestoneIssues.filter((issue) => !isBugIssue(issue));

  return releaseTemplate
    .replace(
      "{{enhancements}}",
      enhancements?.map(formatIssue).join("\n") ?? "",
    )
    .replace("{{bug-fixes}}", bugFixes?.map(formatIssue).join("\n") ?? "")
    .replace("{{docker-tag}}", getDockerTag(version))
    .replace("{{download-url}}", getDownloadUrl(version))
    .replace("{{version}}", version)
    .replace("{{checksum}}", checksum.split(" ")[0]);
};

export async function publishRelease({
  version,
  checksum,
  owner,
  repo,
  github,
}: ReleaseProps & { checksum: string }) {
  const payload = {
    owner,
    repo,
    tag_name: version,
    name: getReleaseTitle(version),
    body: await generateReleaseNotes({ version, checksum, github, owner, repo }),
    draft: true,
    prerelease: /rc/i.test(version),
    make_latest: await isLatestRelease({ version, github, owner, repo }),
  };

  return github.rest.repos.createRelease(payload);
}
