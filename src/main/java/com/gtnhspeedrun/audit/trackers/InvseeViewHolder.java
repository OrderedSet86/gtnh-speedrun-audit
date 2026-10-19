package com.gtnhspeedrun.audit.trackers;

/**
 * Give a ServerUtilities {@code InvseeContainer} the id of the {@code InvseeContainer}'s {@link InvseeViews} view. A
 * mixin implements this interface on {@code InvseeContainer}.
 *
 * <p>
 * When a new view replaces an open view, the open view is ended by this id.
 */
public interface InvseeViewHolder {

    String audit$viewId(); // noqa: NAR130
}
