import { c, t } from "ttag";

import { useUserSetting } from "metabase/common/hooks";
import { useHasModels } from "metabase/common/hooks/use-has-models";
import CollapseSection from "metabase/components/CollapseSection";
import { DelayedLoadingAndErrorWrapper } from "metabase/components/LoadingAndErrorWrapper/DelayedLoadingAndErrorWrapper";
import CS from "metabase/css/core/index.css";
import { Flex, Skeleton } from "metabase/ui";

import { PaddedSidebarLink, SidebarHeading } from "../MainNavbar.styled";
import type { SelectedItem } from "../types";
import { useHasMetrics } from "../utils";

export const BrowseNavSection = ({
  nonEntityItem,
  onItemSelect,
  hasDataAccess,
}: {
  nonEntityItem: SelectedItem;
  onItemSelect: () => void;
  hasDataAccess: boolean;
}) => {
  const BROWSE_MODELS_URL = "/browse/models";
  const BROWSE_DATA_URL = "/browse/databases";
  const BROWSE_METRICS_URL = "/browse/metrics";

  const {
    hasModels,
    isLoading: areModelsLoading,
    error: modelsError,
  } = useHasModels();
  const noModelsExist = hasModels === false;

  const {
    hasMetrics,
    isLoading: areMetricsLoading,
    error: metricsError,
  } = useHasMetrics();
  const noMetricsExist = hasMetrics === false;

  const [expandBrowse = true, setExpandBrowse] = useUserSetting(
    "expand-browse-in-nav",
  );

  if (noModelsExist && noMetricsExist && !hasDataAccess) {
    return null;
  }

  return (
    <CollapseSection
      header={
        <SidebarHeading>{c("A verb, shown in the sidebar")
          .t`Browse`}</SidebarHeading>
      }
      initialState={expandBrowse ? "expanded" : "collapsed"}
      iconPosition="right"
      iconSize={8}
      headerClass={CS.mb1}
      onToggle={setExpandBrowse}
    >
      <DelayedLoadingAndErrorWrapper
        loading={areModelsLoading}
        error={modelsError}
        loader={<SidebarSkeleton />}
        delay={0}
      >
        {!noModelsExist && (
          <PaddedSidebarLink
            icon="model"
            url={BROWSE_MODELS_URL}
            isSelected={nonEntityItem?.url?.startsWith(BROWSE_MODELS_URL)}
            onClick={onItemSelect}
            aria-label={t`Browse models`}
          >
            {t`Models`}
          </PaddedSidebarLink>
        )}
      </DelayedLoadingAndErrorWrapper>
      {hasDataAccess && (
        <PaddedSidebarLink
          icon="database"
          url={BROWSE_DATA_URL}
          isSelected={nonEntityItem?.url?.startsWith(BROWSE_DATA_URL)}
          onClick={onItemSelect}
          aria-label={t`Browse databases`}
        >
          {t`Databases`}
        </PaddedSidebarLink>
      )}

      <DelayedLoadingAndErrorWrapper
        loading={areMetricsLoading}
        error={metricsError}
        loader={<SidebarSkeleton />}
        delay={0}
      >
        {!noMetricsExist && (
          <PaddedSidebarLink
            icon="metric"
            url={BROWSE_METRICS_URL}
            isSelected={nonEntityItem?.url?.startsWith(BROWSE_METRICS_URL)}
            onClick={onItemSelect}
            aria-label={t`Browse metrics`}
          >
            {t`Metrics`}
          </PaddedSidebarLink>
        )}
      </DelayedLoadingAndErrorWrapper>
    </CollapseSection>
  );
};

function SidebarSkeleton() {
  return (
    <Flex py="sm" px="md" h="32.67px" gap="sm" align="center">
      <Skeleton radius="md" h="md" w="md" />
      <Skeleton radius="xs" w="4rem" h="1.2rem" />
    </Flex>
  );
}
