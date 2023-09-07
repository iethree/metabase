import inquirer from "inquirer";
import "zx/globals";

import { getCache, updateCache } from "./cache";

export function success(text: string) {
  console.log(chalk.bold.green(`✔ ${text}`));
}

export function error(text: string | Error) {
  console.log(chalk.bold.red(`✗ ${text}`));
}

export function warn(text: string | Error) {
  console.log(chalk.bold.yellow(`⚠ ${text}`));
}

export function print(text: string) {
  console.log("  " + chalk.bold(text));
}

export async function loading(text: string, fn: () => Promise<void>) {
  await spinner(text, async () => {
    await fn()
      .then(() => success(text))
      .catch(error);
  });
}

export async function step(name: string, fn: () => Promise<void>) {
  const cache = getCache();

  if (cache?.completedSteps?.includes?.(name)) {
    return;
  }

  return fn()
    .then(() => {
      const completedSteps = Array.isArray(cache.completedSteps)
        ? [...cache.completedSteps, name]
        : [name];

      updateCache({ completedSteps });
    })
    .catch(async (err) => {
      print("\n  " + chalk.yellow(err) + "\n");

      const { next } = await inquirer.prompt([
        {
          message: chalk.blue(`What would you like to do?`),
          name: "next",
          type: "list",
          choices: [
            { name: "Retry", value: "retry" },
            { name: "Skip", value: "skip" },
            { name: "Abort", value: "abort" },
          ],
        },
      ]);

      const friendlyName = name.replace(/_-/g, " ");

      switch (next) {
        case "retry":
          await step(name, fn);
          break;
        case "skip":
          await warn(`Skipping ${friendlyName}`);
          break;
        default:
          await error(`Aborting ${friendlyName}`);
          updateCache({ failedStep: name });
          process.exit(1);
      }
    });
}
