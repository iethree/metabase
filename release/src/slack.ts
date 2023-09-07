import { WebClient } from "@slack/web-api";
const slack = new WebClient(process.env.SLACK_BOT_TOKEN);

import { getCache, updateCache } from "./cache";
import { SLACK_CHANNEL_NAME } from "./constants";

export async function sendSlackMessage(text: string) {
  const cache = getCache();

  const blocks = [
    {
      type: "header",
      text: {
        type: "plain_text",
        text: `:rocket: Release ${cache.version} :rocket:`,
        emoji: true,
      },
    },
    {
      type: "section",
      text: {
        type: "mrkdwn",
        text: ":loading: " + text,
      },
    },
  ];

  const response = await slack.chat.postMessage({
    channel: SLACK_CHANNEL_NAME,
    blocks,
    text,
  });

  updateCache({
    slackMessageId: response.ts,
    slackBlocks: blocks,
    slackChannelId: response.channel,
  });
}

export async function updateSlackMessage(text: string) {
  const { slackMessageId, slackChannelId, slackBlocks } = getCache();

  if (!slackMessageId) {
    return sendSlackMessage(text);
  }

  const slackMessageText = slackBlocks[1].text.text;
  const updatedMessageText = `${slackMessageText.replace(
    /:loading:/g,
    ":white_check_mark:",
  )}\n:loading: ${text}`;
  slackBlocks[1].text.text = updatedMessageText;

  await slack.chat.update({
    channel: slackChannelId, // this api doesn't support channel names
    ts: slackMessageId,
    blocks: slackBlocks,
    text: updatedMessageText,
    as_user: true,
  });

  updateCache({ slackBlocks });
}

export async function sendSlackReply(text: string) {
  const { slackMessageId } = getCache();
  const response = await slack.chat.postMessage({
    channel: SLACK_CHANNEL_NAME,
    thread_ts: slackMessageId,
    text,
  });
}
