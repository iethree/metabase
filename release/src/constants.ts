export const S3_PUBLIC_BUCKET =
  process.env.S3_STATIC_BUCKET ?? "static.metabase.com";
export const S3_DOWNLOADS_BUCKET =
  process.env.S3_DOWNLOADS_BUCKET ?? "downloads.metabase.com";
export const PUBLIC_CLOUDFRONT_DISTRIBUTION_ID =
  process.env.STATIC_CLOUDFRONT_DISTRIBUTION_ID ?? "E1HU16PWP1JPMC";
export const DOWNLOADS_CLOUDFRONT_DISTRIBUTION_ID =
  process.env.DOWNLOADS_CLOUDFRONT_DISTRIBUTION_ID ?? "E35CJLWZIZVG7K";
export const SLACK_CHANNEL_NAME = "bot-testing"; // TODO: releases
export const DOCKERHUB_OWNER = "metabase";
export const DOCKERHUB_REPO = "metabase";
