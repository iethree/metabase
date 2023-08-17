/* eslint-disable react/prop-types */
import { useState, useMemo, useEffect } from "react";
import { usePrevious } from "react-use";
import { connect } from "react-redux";
import { t } from "ttag";
import _ from "underscore";

import { useListSelect } from "metabase/hooks/use-list-select";
import { getCollectionsById } from "metabase/selectors/collection";
import Button from "metabase/core/components/Button";
import BulkActionBar from "metabase/components/BulkActionBar";
import Card from "metabase/components/Card";
import PageHeading from "metabase/components/type/PageHeading";
import StackedCheckBox from "metabase/components/StackedCheckBox";
import VirtualizedList from "metabase/components/VirtualizedList";

import Search from "metabase/entities/search";

import { getIsNavbarOpen, openNavbar } from "metabase/redux/app";
import { getUserIsAdmin } from "metabase/selectors/user";
import { isSmallScreen, getMainElement } from "metabase/lib/dom";
import ArchivedItem from "../../components/ArchivedItem";

import {
  ArchiveBarContent,
  ArchiveBarText,
  ArchiveBody,
  ArchiveEmptyState,
  ArchiveHeader,
  ArchiveRoot,
} from "./ArchiveApp.styled";

// ! CONVERT THESE TO SELECTORS
const mapStateToProps = (state, props) => ({
  isNavbarOpen: getIsNavbarOpen(state),
  isAdmin: getUserIsAdmin(state, props),
  collectionsById: getCollectionsById(state),
});

const mapDispatchToProps = {
  openNavbar,
};

const ROW_HEIGHT = 68;

function ArchiveApp({ collectionsById, isAdmin, isNavbarOpen, list, reload }) {
  useEffect(() => {
    if (!isSmallScreen()) {
      openNavbar();
    }
  }, []);

  const [itemList, setItemList] = useState([]);
  const { selected, toggleItem, toggleAll, getIsSelected, clear } =
    useListSelect(item => `${item.model}:${item.id}`);

  const prevList = usePrevious(list);
  useEffect(() => {
    if (_.isEqual(prevList, list)) {
      return;
    }

    setItemList(
      list.map(item => ({
        item,
        readOnly: !collectionsById?.[item.getCollection().id]?.can_write,
      })),
    );
  }, [collectionsById, list, prevList]);

  const mainElement = useMemo(() => getMainElement(), []);

  return (
    <ArchiveRoot>
      <ArchiveHeader>
        <PageHeading>{t`Archive`}</PageHeading>
      </ArchiveHeader>
      <ArchiveBody>
        <Card
          style={{
            height: itemList.length > 0 ? ROW_HEIGHT * itemList.length : "auto",
          }}
        >
          {itemList.length > 0 ? (
            <VirtualizedList
              scrollElement={mainElement}
              items={itemList}
              rowHeight={ROW_HEIGHT}
              renderItem={({ item: { item, readOnly } }) => (
                <ArchivedItem
                  type={item.type}
                  name={item.getName()}
                  icon={item.getIcon().name}
                  color={item.getColor()}
                  isAdmin={isAdmin}
                  onUnarchive={
                    item.setArchived
                      ? async () => {
                          await item.setArchived(false);
                          reload();
                        }
                      : null
                  }
                  onDelete={
                    item.delete
                      ? async () => {
                          await item.delete();
                          reload();
                        }
                      : null
                  }
                  selected={getIsSelected(item)}
                  onToggleSelected={() => toggleItem(item)}
                  showSelect={selected.length > 0}
                  readOnly={readOnly}
                />
              )}
            />
          ) : (
            <ArchiveEmptyState>
              <h2>{t`Items you archive will appear here.`}</h2>
            </ArchiveEmptyState>
          )}
        </Card>
      </ArchiveBody>
      <BulkActionBar isNavbarOpen={isNavbarOpen} showing={selected.length > 0}>
        <ArchiveBarContent>
          <SelectionControls
            allSelected={
              selected.length ===
              itemList.filter(({ readOnly }) => !readOnly).length
            }
            toggleAll={() => {
              const unselected = itemList
                .map(({ item }) => item)
                .filter(item => !getIsSelected(item));
              toggleAll(unselected);
            }}
            clear={clear}
          />
          <BulkActionControls selected={selected} reload={reload} />
          <ArchiveBarText>{t`${selected.length} items selected`}</ArchiveBarText>
        </ArchiveBarContent>
      </BulkActionBar>
    </ArchiveRoot>
  );
}

export default _.compose(
  Search.loadList({
    query: { archived: true },
    reload: true,
    wrapped: true,
  }),
  connect(mapStateToProps, mapDispatchToProps),
)(ArchiveApp);

const BulkActionControls = ({ selected, reload }) => (
  <span>
    <Button
      className="ml1"
      medium
      onClick={async () => {
        try {
          await Promise.all(
            selected.map(item => item.setArchived && item.setArchived(false)),
          );
        } finally {
          reload();
        }
      }}
    >{t`Unarchive`}</Button>
    <Button
      className="ml1"
      medium
      onClick={async () => {
        try {
          await Promise.all(selected.map(item => item.delete && item.delete()));
        } finally {
          reload();
        }
      }}
    >{t`Delete`}</Button>
  </span>
);

const SelectionControls = ({ allSelected, toggleAll, clear }) =>
  allSelected ? (
    <StackedCheckBox checked={true} onChange={clear} />
  ) : (
    <StackedCheckBox checked={false} onChange={toggleAll} />
  );
