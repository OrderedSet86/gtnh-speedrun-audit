package com.gtnhspeedrun.audit.trackers;

/** Added to ServerUtilities' InvseeContainer by the audit mixin, so a new view can find the one it replaces. */
public interface InvseeViewHolder {

    String audit$viewId();
}
