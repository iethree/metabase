/**
 * This implements a simple local cache that lets you pick up and leave off the build whenever you want
 *
 */

import { CACHE_PATH } from "./constants";
import type { CacheType } from "./types";

export function getCache() {
  const cache = fs.readJSONSync(CACHE_PATH, { throws: false }) || {};

  if (!cache) {
    resetCache();
    return {};
  }
  return cache;
}

export function updateCache(values: CacheType) {
  const cache = fs.readJSONSync(CACHE_PATH, { throws: false }) || {};
  const newCache = { ...cache, ...values };
  fs.writeJSONSync(CACHE_PATH, newCache, { spaces: 2 });
  return newCache;
}

export function resetCache() {
  fs.writeJSONSync(CACHE_PATH, {});
}
