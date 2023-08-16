import { t } from "ttag";
import type {
  ClickActionBase,
  DrillMLv2,
} from "metabase/visualizations/click-actions/types";
import type { Dispatch } from "metabase-types/store";
import * as Lib from "metabase-lib";

type AggregationOperator = "sum" | "avg" | "distinct";

const ACTIONS: Record<AggregationOperator, Omit<ClickActionBase, "name">> = {
  sum: {
    title: t`Sum`,
    section: "sum",
    buttonType: "token",
  },
  avg: {
    title: t`Avg`,
    section: "sum",
    buttonType: "token",
  },
  distinct: {
    title: t`Distinct values`,
    section: "sum",
    buttonType: "token",
  },
};

export const SummarizeColumnDrill: DrillMLv2<
  Lib.SummarizeColumnDrillThruInfo
> = ({ drill, drillDisplayInfo, applyDrill }) => {
  if (!drill) {
    return [];
  }

  const { aggregations } = drillDisplayInfo;

  return aggregations.map(operator => ({
    name: operator,
    ...ACTIONS[operator],
    question: () => applyDrill(drill, operator),
    action: () => (dispatch: Dispatch) =>
      // HACK: drill through closes sidebars, so open sidebar asynchronously
      setTimeout(() => dispatch({ type: "metabase/qb/EDIT_SUMMARY" })),
  }));
};
