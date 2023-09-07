import "zx/globals";
$.verbose = false;
import type { ReleaseProps, VersionInfo, VersionInfoFile } from "./types";

import { getDownloadUrl, getReleaseTitle } from "./release-notes";
import { getVersionInfoUrl } from "./version-info";
import { success, error } from "./formatting";
import { isEnterpriseVersion } from "./version-helpers";

export async function checkRelease({ github, version, owner, repo }: ReleaseProps & { owner: string, repo: string }) {
  let releaseSuccessful = true;

  // verify jar exists
  const jar = await fetch(getDownloadUrl(version));

  if (!jar.ok) {
    error(`Jar for version ${version} not found at ${getDownloadUrl(version)}`);
    releaseSuccessful = false;
  }
  success(`JAR found for ${version}`);

  // verify release notes exist
  const releases = await github.rest.repos.listReleases({
    owner,
    repo,
    per_page: 100,
  });

  const release = releases.data.find((r) => r.tag_name === version && r.name === getReleaseTitle(version));

  if (!release) {
    error(`Release for version ${version} not found`);
    releaseSuccessful = false;
  }
  success(`Release notes found for ${version}`);

  // verify docker tag exists
  const dockerTagData = await fetch(
    `https://hub.docker.com/v2/repositories/metabase/${isEnterpriseVersion(version) ? 'metabase-enterprise' : 'metabase'}/tags/?page_size=10&page=1&name=${version}`
  ).then((r) => r.json());

  if (!dockerTagData.results.find((tag: { name: string } )=> tag.name === version)) {
    error(`Docker tag for version ${version} not found`);
    releaseSuccessful = false;
  }

  success(`Docker tag found for ${version}`);

  // verify version-info.json is updated
  const versionInfo: VersionInfoFile = await fetch(getVersionInfoUrl(version)).then((r) => r.json());

  const foundVersionInfo =
    versionInfo.latest.version === version ||
    versionInfo.older.some((info: VersionInfo) => info.version === version);

  if (!foundVersionInfo) {
    error(`Version ${version} not found in version-info.json`);
    releaseSuccessful = false;
  }

  success(`Version info found for ${version}`);

  return releaseSuccessful;
}
