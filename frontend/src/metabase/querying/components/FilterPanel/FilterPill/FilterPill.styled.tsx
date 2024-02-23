import styled from "@emotion/styled";

import { alpha, color } from "metabase/lib/colors";
import type { FlexProps } from "metabase/ui";
import { Flex } from "metabase/ui";

export const FilterPillRoot = styled(Flex)<FlexProps>`
  cursor: pointer;
  color: ${color("filter")};
  background-color: ${alpha("filter", 0.2)};
  border-radius: 0.75rem;
`;