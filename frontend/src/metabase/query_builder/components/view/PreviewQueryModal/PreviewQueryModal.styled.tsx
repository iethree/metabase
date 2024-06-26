import styled from "@emotion/styled";

import ExternalLink from "metabase/core/components/ExternalLink";

export const ModalExternalLink = styled(ExternalLink)`
  color: var(--mb-color-brand);
  font-size: 0.75rem;
  line-height: 1rem;
  font-weight: bold;
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
`;
