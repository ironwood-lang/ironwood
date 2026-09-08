// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/**
 * Second page of the new-project wizard: what to put in the project.
 *
 * <p>The package name defaults to one derived from the project name, so the
 * common case is to read the page and press Finish.
 */
public final class IronwoodProjectSettingsPage extends WizardPage {

    private Text packageField;
    private Button createSample;
    private String suggestedPackage = "";

    public IronwoodProjectSettingsPage() {
        super("ironwoodProjectSettings");
        setTitle("Ironwood Project");
        setDescription("Choose where the generated source goes.");
    }

    @Override
    public void createControl(Composite parent) {
        Composite panel = new Composite(parent, SWT.NONE);
        panel.setLayout(new GridLayout(2, false));
        panel.setLayoutData(new GridData(GridData.FILL_BOTH));

        new Label(panel, SWT.NONE).setText("&Package:");
        packageField = new Text(panel, SWT.BORDER);
        packageField.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        packageField.addModifyListener(event -> validate());

        createSample = new Button(panel, SWT.CHECK);
        createSample.setText("Create a sample program and test suite");
        createSample.setSelection(true);
        GridData span = new GridData(GridData.FILL_HORIZONTAL);
        span.horizontalSpan = 2;
        createSample.setLayoutData(span);

        Label note = new Label(panel, SWT.WRAP);
        note.setText("Sources go in src/main/ironwood and tests in src/test/ironwood."
                + " The sample builds and runs as created.");
        GridData noteData = new GridData(GridData.FILL_HORIZONTAL);
        noteData.horizontalSpan = 2;
        noteData.verticalIndent = 12;
        note.setLayoutData(noteData);

        setControl(panel);
        validate();
    }

    /**
     * Offers a package derived from the project name, but never overwrites a
     * package the reader has already typed.
     */
    public void suggestPackageFor(String projectName) {
        String suggestion = IronwoodProjectTemplates.packageNameFor(projectName);
        if (packageField == null) {
            suggestedPackage = suggestion;
            return;
        }
        String current = packageField.getText().trim();
        if (current.isEmpty() || current.equals(suggestedPackage)) {
            packageField.setText(suggestion);
        }
        suggestedPackage = suggestion;
    }

    private void validate() {
        String problem = IronwoodProjectTemplates.validatePackageName(packageName());
        setErrorMessage(problem);
        setPageComplete(problem == null);
    }

    public String packageName() {
        return packageField == null ? suggestedPackage : packageField.getText().trim();
    }

    public boolean createSampleSource() {
        return createSample == null || createSample.getSelection();
    }
}
