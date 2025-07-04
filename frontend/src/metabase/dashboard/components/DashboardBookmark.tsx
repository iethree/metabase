import BookmarkToggle from "metabase/common/components/BookmarkToggle";
import { useBookmarkListQuery } from "metabase/common/hooks";
import { getDashboard } from "metabase/dashboard/selectors";
import Bookmark from "metabase/entities/bookmarks";
import { useDispatch, useSelector } from "metabase/lib/redux";
import { useRegisterShortcut } from "metabase/palette/hooks/useRegisterShortcut";
import type { DashboardId, Bookmark as IBookmark } from "metabase-types/api";

export interface DashboardBookmarkProps {
  isBookmarked: boolean;
}

export const DashboardBookmark = (): JSX.Element | null => {
  const { data: bookmarks = [] } = useBookmarkListQuery();

  const dispatch = useDispatch();

  const dashboard = useSelector(getDashboard);

  const isBookmarked = dashboard
    ? getIsBookmarked({
        dashboardId: dashboard.id,
        bookmarks,
      })
    : false;

  const handleCreateBookmark = () => {
    if (dashboard) {
      const id = dashboard.id;
      dispatch(Bookmark.actions.create({ id, type: "dashboard" }));
    }
  };

  const handleDeleteBookmark = () => {
    if (dashboard) {
      const id = dashboard.id;
      dispatch(Bookmark.actions.delete({ id, type: "dashboard" }));
    }
  };

  useRegisterShortcut(
    [
      {
        id: "dashboard-bookmark",
        perform: () =>
          isBookmarked ? handleDeleteBookmark() : handleCreateBookmark(),
      },
    ],
    [isBookmarked],
  );

  return (
    <BookmarkToggle
      isBookmarked={isBookmarked}
      onCreateBookmark={handleCreateBookmark}
      onDeleteBookmark={handleDeleteBookmark}
    />
  );
};

type IsBookmarkedSelectorProps = {
  bookmarks: IBookmark[];
  dashboardId: DashboardId;
};

export const getIsBookmarked = ({
  bookmarks,
  dashboardId,
}: IsBookmarkedSelectorProps) =>
  bookmarks.some(
    (bookmark) =>
      bookmark.type === "dashboard" && bookmark.item_id === dashboardId,
  );
