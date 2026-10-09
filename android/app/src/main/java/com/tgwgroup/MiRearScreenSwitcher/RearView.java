package com.tgwgroup.MiRearScreenSwitcher;

import android.os.Bundle;
import android.view.View;

/** One rear-screen experience hosted inside RearHostActivity. */
public interface RearView {
    View getView();
    /** Show or refresh with this payload. Called again for updates while already visible. */
    void bind(Bundle payload);
    /** Stop timers/animations; the view is being swapped out or the host is going away. */
    void unbind();
}
