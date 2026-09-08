// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTabGroup;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * The single tab of the Ironwood Application launch dialog.
 *
 * <p>An Ironwood launch needs three things, and the tab asks for exactly those:
 * the project to link from, the qualified type holding {@code main}, and the
 * program's arguments. Everything else follows from the project layout.
 */
public final class IronwoodLaunchTab extends AbstractLaunchConfigurationTab {

    private Text projectField;
    private Text mainClassField;
    private Text argumentsField;

    @Override
    public void createControl(Composite parent) {
        Composite panel = new Composite(parent, SWT.NONE);
        panel.setLayout(new GridLayout(2, false));
        panel.setLayoutData(new GridData(GridData.FILL_BOTH));

        projectField = addField(panel, "&Project:");
        mainClassField = addField(panel, "&Main class:");
        argumentsField = addField(panel, "Program &arguments:");

        setControl(panel);
    }

    private Text addField(Composite parent, String label) {
        new Label(parent, SWT.NONE).setText(label);
        Text field = new Text(parent, SWT.BORDER);
        field.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        field.addModifyListener(event -> updateLaunchConfigurationDialog());
        return field;
    }

    @Override
    public void setDefaults(ILaunchConfigurationWorkingCopy configuration) {
        configuration.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_PROJECT, "");
        configuration.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_MAIN_CLASS, "");
        configuration.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_ARGUMENTS, "");
    }

    @Override
    public void initializeFrom(ILaunchConfiguration configuration) {
        projectField.setText(read(configuration, IronwoodLaunchDelegate.ATTRIBUTE_PROJECT));
        mainClassField.setText(read(configuration, IronwoodLaunchDelegate.ATTRIBUTE_MAIN_CLASS));
        argumentsField.setText(read(configuration, IronwoodLaunchDelegate.ATTRIBUTE_ARGUMENTS));
    }

    private static String read(ILaunchConfiguration configuration, String attribute) {
        try {
            return configuration.getAttribute(attribute, "");
        } catch (CoreException error) {
            return "";
        }
    }

    @Override
    public void performApply(ILaunchConfigurationWorkingCopy configuration) {
        configuration.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_PROJECT,
                projectField.getText().trim());
        configuration.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_MAIN_CLASS,
                mainClassField.getText().trim());
        configuration.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_ARGUMENTS,
                argumentsField.getText().trim());
    }

    @Override
    public boolean isValid(ILaunchConfiguration configuration) {
        setErrorMessage(null);
        String projectName = projectField.getText().trim();
        if (projectName.isEmpty()) {
            setErrorMessage("Name the project to run.");
            return false;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isAccessible()) {
            setErrorMessage("Project '" + projectName + "' is not open.");
            return false;
        }
        if (mainClassField.getText().trim().isEmpty()) {
            setErrorMessage("Name the qualified class declaring main.");
            return false;
        }
        return true;
    }

    @Override
    public String getName() {
        return "Main";
    }

    /** Supplies the tab list for the Ironwood Application launch type. */
    public static final class Group extends AbstractLaunchConfigurationTabGroup {

        @Override
        public void createTabs(ILaunchConfigurationDialog dialog, String mode) {
            setTabs(new ILaunchConfigurationTab[] {
                    new IronwoodLaunchTab(),
                    new org.eclipse.debug.ui.CommonTab(),
            });
        }
    }
}
