package org.tvrenamer.view;

import static org.eclipse.swt.SWT.*;

public enum SWTMessageBoxType {
    DLG_ERR(ICON_ERROR | OK | CANCEL),
    DLG_WARN(ICON_WARNING | OK | CANCEL),
    DLG_OK(OK | CANCEL);

    private final int swtIconValue;
    SWTMessageBoxType(int swtIconValue) {
        this.swtIconValue = swtIconValue;
    }

    public int getSwtIconValue() {
        return swtIconValue;
    }
}
