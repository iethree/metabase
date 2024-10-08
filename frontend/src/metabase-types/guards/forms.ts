import type {
  CustomFormFieldDefinition,
  FormFieldDefinition,
  StandardFormFieldDefinition,
} from "metabase-types/forms";

import { isReactComponent } from "./react";

export function isCustomWidget(
  formField: FormFieldDefinition,
): formField is CustomFormFieldDefinition {
  return (
    !(formField as StandardFormFieldDefinition).type &&
    isReactComponent((formField as CustomFormFieldDefinition).widget)
  );
}
