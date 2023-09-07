/**
 * this script coordinates the release process
 * everything of import is actually done in the cloud via github actions
 */

import "dotenv/config";
import "zx/globals";
import inquirer from "inquirer";
$.verbose = false;
import open from "open";
import { Octokit } from "@octokit/rest";
const github = new Octokit({ auth: process.env.GITHUB_TOKEN });

const owner = "iethree"; // FIXME

import type { CacheType } from "../src/types";

import {
  success,
  loading,
  step,
} from "../src/formatting";

import {
  updateCache,
  getCache,
} from "../src/cache";

import {
  normalizeVersionNumber,
  getVersionType,
  getOSSVersion,
  getEnterpriseVersion,
  getMajorVersion,
  getReleaseBranch,
  isValidVersionString,
} from "../src/version-helpers";

import {
  sendSlackMessage,
  updateSlackMessage,
  sendSlackReply,
} from "../src/slack";
import { getChecksForRef, tagRelease } from "../src/github";

let cache: CacheType = getCache();

console.log(`\n${chalk.bold.blue("🚀 [TEST] Metabase Release Helper 🚀")}\n`);

await step("check-credentials", async () => {
  const requiredCredentials = ["GITHUB_TOKEN", "SLACK_BOT_TOKEN"];

  requiredCredentials.forEach((credential) => {
    if (!process.env?.[credential]?.length) {
      throw new Error(`Missing required credential: ${credential}`);
    }
  });

  // export credentials to env
  requiredCredentials.forEach(async (credential) => {
    await $`export ${credential}=${process.env[credential]}`;
  });
});

await step("version-select", async () => {
  if (process.argv[2]) {
    const version = normalizeVersionNumber(process.argv[2]);
    const versionType = getVersionType(version);

    cache = updateCache({ version, versionType });
  }

  if (!cache.version) {
    const { version } = await inquirer.prompt([
      {
        message: 'What version are you releasing (eg. "x.75.2.2")',
        name: "version",
        // TODO: validate version
      },
    ]);
    const normalizedVersion = normalizeVersionNumber(version);
    const versionType = getVersionType(normalizedVersion);
    cache = updateCache({ version: normalizedVersion, versionType });
  }

  if (!cache.version || !isValidVersionString(cache.version)) {
    throw new Error(`We need a version to release`);
  }

  const majorVersion = getMajorVersion(cache.version);
  const releaseBranch = getReleaseBranch(cache.version);

  cache = updateCache({ majorVersion, releaseBranch });
});

await step("send-release-start-to-slack", () => {
  const text = `Starting release of Metabase ${cache.version} from ${cache.releaseBranch}`;
  return sendSlackMessage(text);
});

await step("release-type", async () => {
  const { releaseType } = await inquirer.prompt([
    {
      message: "What type of release is this?",
      name: "releaseType",
      type: "list",
      choices: [
        { name: "Public (most releases)", value: "public" },
        { name: "Private (security releases)", value: "private" },
      ],
    },
  ]);

  cache = updateCache({ releaseType });
});

// we do this here only after we've checked credentials
const repo = cache.releaseType === "public" ? "metabase" : "metabase-private";

await step("notify-approvers", async () => {
  const releaseBranchLink = `https://github.com/metabase/${repo}/commits/${cache.releaseBranch}`;
  await sendSlackReply(
    `:githubmerge: You can check the <${releaseBranchLink}|release commits here>`,
  );
});

// await step('sync metabase-private', async () => {

// });

await step("get-release-commit", async () => {
  const ref = await github.rest.git.getRef({
    owner,
    repo,
    ref: `heads/${cache.releaseBranch}`,
  });

  cache = updateCache({ commitHash: ref.data.object.sha.trim() });
});

await step("check-milestone", async () => {
  const milestones = await github.rest.issues.listMilestones({
    owner,
    repo,
    state: "open",
  });

  const { milestoneId } = await inquirer.prompt([
    {
      message: `What milestone do you want to release?`,
      name: "milestoneId",
      type: "list",
      choices: [
        ...(milestones.data.map((milestone) => ({
          name: milestone.title,
          value: milestone.number,
        })) ?? []),
        { name: "None (e.g. patch releases)", value: "none" },
      ],
    },
  ]);

  if (milestoneId !== "none") {
    const milestoneLink = `https://github.com/metabase/metabase/milestone/${milestoneId}`;
    const milestoneName = milestones.data.find(
      (milestone) => milestone.number === milestoneId,
    )?.title;

    await open(milestoneLink);
    await updateSlackMessage(`Checking milestone ${milestoneName}`);
    await sendSlackReply(
      `:rock: Releasing <${milestoneLink}| Milestone ${milestoneName}>`,
    );

    const { milestoneCorrect } = await inquirer.prompt([
      {
        message: `Is the milestone you're releasing in order?`,
        name: "milestoneCorrect",
        type: "list",
        choices: [
          { name: "Yes", value: true },
          { name: "No", value: false },
        ],
      },
    ]);

    if (!milestoneCorrect) {
      throw new Error(`resume the build when the milestone is correct`);
    }

    cache = updateCache({ milestoneId });
  }

  await sendSlackReply(`:hammer_and_wrench: @core-ems, OK to release?`);
  await sendSlackReply(`:artist: @pms, OK to release?`);
  await sendSlackReply(`:success: @successengineers, OK to release?`);
});

await step("check-green-ci", async () => {
  if (!cache.commitHash) {
    throw new Error(`We need a commit hash to check CI`);
  }

  await updateSlackMessage(`Checking latest CI run`);

  const { failedChecks, successfulChecks} = await getChecksForRef({
    ref: cache.commitHash,
    github,
    repo,
    owner,
  });

  if (failedChecks.length > 0) {
    throw new Error(
      `failed checks found: ${failedChecks
        .map((check) => check.name)
        .join(", ")}`,
    );
  }

  success(`${successfulChecks.length} successful checks`);
});

await step("trigger-pre-release-action", async () => {
  await updateSlackMessage(
    `Running pre-release checks and build via github actions`,
  );

  if (!cache.commitHash || !cache.version || !cache.releaseBranch) {
    throw new Error(
      `We need a commit hash and version to run the pre-release job`,
    );
  }

  const response = await github.rest.actions.createWorkflowDispatch({
    owner,
    repo: "metabase-private",
    workflow_id: "pre-release.yml",
    ref: "master",
    inputs: {
      commit: cache.commitHash,
      version: getOSSVersion(cache.version), // probably should normalize this more
    },
  });

  if (response.status !== 204) {
    throw new Error(`pre-release action trigger failed: ${response}`);
  }
  success("started pre-release github action");
});

await step("check-pre-release-action", async () => {
  await loading(
    `waiting for pre-release action to finish (usually about 40 min)`,
    async () => {
      await $`sleep 20`;

      const response = await github.rest.actions.listWorkflowRuns({
        owner,
        repo: "metabase-private",
        workflow_id: "pre-release.yml",
      });

      const run = response.data.workflow_runs[0];

      cache = updateCache({ preReleaseActionId: run.id });
      await sendSlackReply(
        `:workingonit: You can monitor the build process <${run.html_url}|here>`,
      );
      open(run.html_url);

      if (run.conclusion === "failure") {
        throw new Error(`pre-release action failed`);
      }

      if (!cache.preReleaseActionId) {
        throw new Error(
          `We need a pre-release action id to check the pre-release job`,
        );
      }

      let i = 0;
      do {
        const response = await github.rest.actions.getWorkflowRun({
          owner,
          repo: "metabase-private",
          run_id: cache.preReleaseActionId,
        });

        const run = response?.data;

        if (run.conclusion === "failure") {
          throw new Error(`pre-release action failed`);
        }

        if (run?.conclusion === "success") {
          success("completed pre-release github action");
          return;
        }
        await $`sleep 60`;
      } while ( ++i < 60);
    });
});

await step("tag-release", async () => {
  if (!cache.commitHash || !cache.version) {
    throw new Error(`We need a commit hash and version to tag the release`);
  }

  await tagRelease({
    github,
    version: cache.version,
    commitHash: cache.commitHash,
    owner,
    repo,
  });

  success(`tagged release v${cache.version} in ${owner}/${repo}`);
});

await step('trigger-release-workflow', async () => {
  if (!cache.commitHash || !cache.version) {
    throw new Error(`We need a commit hash and version to tag the release`);
  }

  const OSSworkflow = await github.rest.actions.createWorkflowDispatch({
    owner,
    repo: "metabase-private",
    workflow_id: "release.yml",
    ref: "master",
    inputs: {
      version: getOSSVersion(cache.version),
    },
  });

  if (OSSworkflow.status !== 204) {
    throw new Error(`failed to trigger OSS release workflow`);
  }

  const EEworkflow = await github.rest.actions.createWorkflowDispatch({
    owner,
    repo: "metabase-private",
    workflow_id: "release.yml",
    ref: "master",
    inputs: {
      version: getEnterpriseVersion(cache.version),
    },
  });

  if (EEworkflow.status !== 204) {
    throw new Error(`failed to trigger EE release workflow`);
  }

  success(`triggered private release workflows for v${cache.version}`);

});

await step("check-release-action", async () => {
  await updateSlackMessage(`Running release action via github actions`);

  await loading(
    `waiting for release action to finish (usually about 40 min)`,
    async () => {
      await $`sleep 10`;

      let i = 0;
      do {
        const response = await github.rest.actions.listWorkflowRuns({
          owner,
          repo,
          workflow_id: "release.yml",
        });

        const run = response.data.workflow_runs[0];

        if (run.conclusion === "failure") {
          throw new Error(`release action failed`);
        }

        if (run.conclusion === "success") {
          break;
        }

        await $`sleep 60`;
      } while ( ++i < 60);
    },
  );

  success("completed release github action");
});

await step('check-release', async () => {


});

console.log(
  chalk.green(`\n🚀 Successfully released Metabase ${cache.version} 🚀`),
);
