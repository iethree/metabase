// should always be in the format x.75.2.3
export const normalizeVersionNumber = (versionString: string) => {
  return "x." + versionString.replace("x.", "");
};

// must be in the format x.75.2.3
export const isValidVersionString = (versionString: string) => {
  return /^x.\d+.+$/.test(versionString);
};

export const getOSSVersion = (versionString: string) => {
  return versionString.replace(/^x\.|^v1\./, "v0.");
};

export const getEnterpriseVersion = (versionString: string) => {
  return versionString.replace(/^x\.|^v0\./, "v1.");
};

export const getVersionType = (versionString: string) => {
  const versionParts = versionString.replace(/.0$/, "").split(".").length;

  if (isRCVersion(versionString)) {
    return "rc"; // x.88-RC2
  }

  switch (versionParts) {
    case 2: // x.88
      return "major";
    case 3: // x.88.2
      return "minor";
    case 4: // x.88.2.3
      return "patch";
    default:
      return "invalid";
  }
};

export const isEnterpriseVersion = (versionString: string) => {
  return /^v1./i.test(versionString);
};

export const isRCVersion = (version: string) => /rc/i.test(version);

export const getMajorVersion = (versionString: string) =>
  versionString.replace(/^[^\.]+\./, "").split(".")[0];

export const getReleaseBranch = (versionString: string) => {
  // regexr.com/7ir4u
  const majorVersion = getMajorVersion(versionString);
  return `release-x.${majorVersion}.x`;
};
