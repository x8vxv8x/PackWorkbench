package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.IOptionDetails;

/**
 * Proxy for IOptionDetails that defers writes to the original task.
 */
public class OptionTempHandler implements IOptionDetails {
    private final IOptionDetails original;
    private boolean tempValue;

    public OptionTempHandler(IOptionDetails original) {
        this.original = original;
        this.tempValue = original.optionValue();
    }

    @Override public String name() { return original.name(); }
    @Override public boolean optionValue() { return tempValue; }
    @Override public void setOptionValue(boolean value) { this.tempValue = value; }
    @Override public String optionDescription() { return original.optionDescription(); }

    public void applyToOriginal() {
        original.setOptionValue(tempValue);
    }
}
