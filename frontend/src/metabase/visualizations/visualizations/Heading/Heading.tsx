import type { MouseEvent } from "react";
import { useEffect, useMemo, useState } from "react";
import { t } from "ttag";

import { useToggle } from "metabase/common/hooks/use-toggle";
import { getParameterValues } from "metabase/dashboard/selectors";
import { useTranslateContent } from "metabase/i18n/hooks";
import { useSelector } from "metabase/lib/redux";
import { isEmpty } from "metabase/lib/validate";
import { fillParametersInText } from "metabase/visualizations/shared/utils/parameter-substitution";
import type {
  Dashboard,
  QuestionDashboardCard,
  VisualizationSettings,
} from "metabase-types/api";

import {
  HeadingContainer,
  HeadingContent,
  InputContainer,
  TextInput,
} from "./Heading.styled";

interface HeadingProps {
  isEditing: boolean;
  onUpdateVisualizationSettings: ({ text }: { text: string }) => void;
  dashcard: QuestionDashboardCard;
  settings: VisualizationSettings;
  dashboard: Dashboard;
}

export function Heading({
  settings,
  isEditing,
  onUpdateVisualizationSettings,
  dashcard,
  dashboard,
}: HeadingProps) {
  const parameterValues = useSelector(getParameterValues);
  const justAdded = useMemo(() => dashcard?.justAdded || false, [dashcard]);

  const tc = useTranslateContent();

  const [isFocused, { turnOn: toggleFocusOn, turnOff: toggleFocusOff }] =
    useToggle(justAdded);
  const isPreviewing = !isFocused;

  const [textValue, setTextValue] = useState(settings.text);
  const preventDragging = (e: MouseEvent<HTMLInputElement>) =>
    e.stopPropagation();

  const translatedText = useMemo(() => tc(settings.text), [settings.text, tc]);

  // handles a case when settings are updated externally
  useEffect(() => {
    setTextValue(settings.text);
  }, [settings.text]);

  const content = useMemo(
    () =>
      fillParametersInText({
        dashcard,
        dashboard,
        parameterValues,
        text: translatedText,
      }),
    [dashcard, dashboard, parameterValues, translatedText],
  );

  const hasContent = !isEmpty(settings.text);
  const placeholder = t`Heading`;

  if (isEditing) {
    return (
      <InputContainer
        data-testid="editing-dashboard-heading-container"
        isEmpty={!hasContent}
        isPreviewing={isPreviewing}
        onClick={toggleFocusOn}
      >
        {isPreviewing ? (
          <HeadingContent
            data-testid="editing-dashboard-heading-preview"
            isEditing={isEditing}
            onMouseDown={preventDragging}
          >
            {hasContent ? settings.text : placeholder}
          </HeadingContent>
        ) : (
          <TextInput
            name="heading"
            data-testid="editing-dashboard-heading-input"
            placeholder={placeholder}
            value={textValue}
            autoFocus={justAdded || isFocused}
            onChange={(e) => setTextValue(e.target.value)}
            onMouseDown={preventDragging}
            onBlur={() => {
              toggleFocusOff();

              if (settings.text !== textValue) {
                onUpdateVisualizationSettings({ text: textValue });
              }
            }}
          />
        )}
      </InputContainer>
    );
  }

  return (
    <HeadingContainer>
      <HeadingContent data-testid="saved-dashboard-heading-content">
        {content}
      </HeadingContent>
    </HeadingContainer>
  );
}
