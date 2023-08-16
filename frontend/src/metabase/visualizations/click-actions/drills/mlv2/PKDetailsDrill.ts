import { t } from "ttag";
import type { DrillMLv2 } from "metabase/visualizations/click-actions/types";
import * as Lib from "metabase-lib";

export const PKDetailsDrill: DrillMLv2<Lib.PKDrillThruInfo> = ({
  drill,
  drillDisplayInfo,
  applyDrill,
}) => {
  if (!drill) {
    return [];
  }

  const { objectId } = drillDisplayInfo;

  return [
    {
      name: "object-detail",
      section: "details",
      title: t`View details`,
      buttonType: "horizontal",
      icon: "expand",
      default: true,
      question: () => applyDrill(drill, objectId),
    },
  ];
};
