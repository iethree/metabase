import { useLayoutEffect, useRef, useState } from "react";
import { t } from "ttag";

import { ToolbarButton } from "metabase/common/components/ToolbarButton";
import {
  addParameter,
  hideAddParameterPopover,
  showAddParameterPopover,
} from "metabase/dashboard/actions/parameters";
import { getIsAddParameterPopoverOpen } from "metabase/dashboard/selectors";
import { useDispatch, useSelector } from "metabase/lib/redux";
import { useRegisterShortcut } from "metabase/palette/hooks/useRegisterShortcut";
import {
  type ParameterSection,
  getDashboardParameterSections,
  getDefaultOptionForParameterSectionMap,
} from "metabase/parameters/utils/dashboard-options";
import { getParameterIconName } from "metabase/parameters/utils/ui";
import { Icon, Menu, Text } from "metabase/ui";

export const AddFilterParameterButton = () => {
  const sections = getDashboardParameterSections();
  const dispatch = useDispatch();
  const isOpened = useSelector(getIsAddParameterPopoverOpen);
  const [rightSectionWidth, setRightSectionWidth] = useState(0);
  const rightSectionWidthRef = useRef(0);

  const handleItemClick = (section: ParameterSection) => {
    const defaultOption = getDefaultOptionForParameterSectionMap()[section.id];
    if (defaultOption) {
      dispatch(addParameter(defaultOption));
    }
  };

  const handleRightSectionRef = (rightSection: HTMLDivElement | null) => {
    if (rightSection) {
      rightSectionWidthRef.current = Math.max(
        rightSectionWidthRef.current,
        rightSection.clientWidth,
      );
    }
  };

  useRegisterShortcut(
    [
      {
        id: "dashboard-add-filter",
        perform: () =>
          isOpened
            ? dispatch(hideAddParameterPopover())
            : dispatch(showAddParameterPopover()),
      },
    ],
    [isOpened],
  );

  useLayoutEffect(() => {
    if (isOpened) {
      setRightSectionWidth(rightSectionWidthRef.current);
    }
  }, [isOpened]);

  return (
    <Menu
      opened={isOpened}
      onClose={() => dispatch(hideAddParameterPopover())}
      position="bottom-end"
      trapFocus
    >
      <Menu.Target>
        <ToolbarButton
          icon="filter"
          onClick={() =>
            isOpened
              ? dispatch(hideAddParameterPopover())
              : dispatch(showAddParameterPopover())
          }
          aria-label={t`Add a filter or parameter`}
          tooltipLabel={t`Add a filter or parameter`}
        />
      </Menu.Target>
      <Menu.Dropdown data-testid="add-filter-parameter-dropdown">
        <Menu.Label>{t`Add a filter or parameter`}</Menu.Label>
        {sections.map((section) => (
          <Menu.Item
            key={section.id}
            leftSection={<Icon name={getParameterIconName(section.id)} />}
            rightSection={
              <Text
                ref={handleRightSectionRef}
                c="inherit"
                miw={rightSectionWidth}
              >
                {section.description}
              </Text>
            }
            aria-label={section.name}
            onClick={() => handleItemClick(section)}
          >
            <Text c="inherit" fw="bold">
              {section.name}
            </Text>
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
};
