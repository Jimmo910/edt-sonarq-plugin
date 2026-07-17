/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.scope.SubsystemNode;
import ru.jimmo.edt.sonarq.core.scope.SubsystemTreeReader;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;

/**
 * Per-project SonarQube binding: project key, fixed branch, path prefix, and the local-analysis
 * scope (base branch to diff against and the subsystems to restrict analysis to).
 */
public class SonarProjectPropertyPage extends PropertyPage
{
    private static final int SUBSYSTEM_TREE_HEIGHT = 120;

    private Text projectKeyText;

    private Text branchText;

    private Text prefixText;

    private Label findResultLabel;

    private Combo baseBranchCombo;

    private CheckboxTreeViewer subsystemViewer;

    private Path projectRoot;

    @Override
    protected Control createContents(Composite parent)
    {
        noDefaultAndApplyButton();
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(2, false));

        new Label(composite, SWT.NONE).setText(Messages.PropertyPage_ProjectKey);
        projectKeyText = new Text(composite, SWT.BORDER);
        projectKeyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectKeyText.setMessage(Messages.PropertyPage_ProjectKey_Hint);
        projectKeyText.setToolTipText(Messages.PropertyPage_ProjectKey_Hint);

        Button findButton = new Button(composite, SWT.PUSH);
        findButton.setText(Messages.PropertyPage_FindKey);
        findButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> findKey()));
        findResultLabel = new Label(composite, SWT.WRAP);
        findResultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(composite, SWT.NONE).setText(Messages.PropertyPage_Branch);
        branchText = new Text(composite, SWT.BORDER);
        branchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label hint = new Label(composite, SWT.WRAP);
        hint.setText(Messages.PropertyPage_BranchHint);
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(composite, SWT.NONE).setText(Messages.PropertyPage_PathPrefix);
        prefixText = new Text(composite, SWT.BORDER);
        prefixText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        prefixText.setMessage(Messages.PropertyPage_PathPrefix_Hint);
        prefixText.setToolTipText(Messages.PropertyPage_PathPrefix_Hint);

        IPath location = getProject().getLocation();
        projectRoot = location == null ? null : Path.of(location.toOSString());
        createScopeGroup(composite);

        ProjectBinding binding = new ProjectBindingStore().load(getProject());
        projectKeyText.setText(binding.projectKey());
        branchText.setText(binding.branchOverride());
        prefixText.setText(binding.pathPrefix());
        loadScope(binding);
        return composite;
    }

    /**
     * Builds the "Analysis scope (local mode)" group: an editable base-branch combo (populated from
     * local git branches, best-effort) and a checkbox tree of the project's subsystems with a Refresh
     * button that re-reads the tree from disk.
     *
     * @param parent the page composite, not {@code null}
     */
    private void createScopeGroup(Composite parent)
    {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.PropertyPage_ScopeGroup);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        new Label(group, SWT.NONE).setText(Messages.PropertyPage_BaseBranch);
        baseBranchCombo = new Combo(group, SWT.DROP_DOWN);
        baseBranchCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label baseBranchHint = new Label(group, SWT.WRAP);
        baseBranchHint.setText(Messages.PropertyPage_BaseBranchHint);
        baseBranchHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(group, SWT.NONE).setText(Messages.PropertyPage_Subsystems);
        Button refreshButton = new Button(group, SWT.PUSH);
        refreshButton.setText(Messages.PropertyPage_RefreshSubsystems);
        refreshButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> refreshSubsystems()));

        subsystemViewer = new CheckboxTreeViewer(group, SWT.BORDER);
        subsystemViewer.setContentProvider(new ITreeContentProvider()
        {
            @Override
            public Object[] getElements(Object inputElement)
            {
                return ((List<?>)inputElement).toArray();
            }

            @Override
            public Object[] getChildren(Object parentElement)
            {
                return ((SubsystemNode)parentElement).children().toArray();
            }

            @Override
            public Object getParent(Object element)
            {
                return null;
            }

            @Override
            public boolean hasChildren(Object element)
            {
                return !((SubsystemNode)element).children().isEmpty();
            }
        });
        subsystemViewer.setLabelProvider(new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((SubsystemNode)element).name();
            }
        });
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        treeData.heightHint = SUBSYSTEM_TREE_HEIGHT;
        subsystemViewer.getTree().setLayoutData(treeData);

        Label subsystemsHint = new Label(group, SWT.WRAP);
        subsystemsHint.setText(Messages.PropertyPage_SubsystemsHint);
        subsystemsHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    /**
     * Pre-fills the base-branch combo and the subsystem tree from the stored binding.
     *
     * @param binding the loaded project binding, not {@code null}
     */
    private void loadScope(ProjectBinding binding)
    {
        baseBranchCombo.setItems(localBranchNames(projectRoot).toArray(new String[0]));
        baseBranchCombo.setText(binding.baseBranch());
        reloadSubsystemTree(new HashSet<>(binding.subsystems()));
    }

    /**
     * Re-reads the subsystem tree from disk, re-checking whichever names are currently checked so a
     * refresh does not silently discard the user's in-progress selection.
     */
    private void refreshSubsystems()
    {
        reloadSubsystemTree(new HashSet<>(checkedSubsystemNames()));
    }

    /**
     * Reloads the subsystem tree input and checks the nodes whose name is in {@code checkedNames}, at
     * any depth.
     *
     * @param checkedNames the subsystem names to check, not {@code null}
     */
    private void reloadSubsystemTree(Set<String> checkedNames)
    {
        List<SubsystemNode> roots = projectRoot == null ? List.of() : SubsystemTreeReader.read(projectRoot);
        subsystemViewer.setInput(roots);
        subsystemViewer.setCheckedElements(collectMatching(roots, checkedNames).toArray());
    }

    /**
     * Collects every node (at any depth) whose name is in {@code names}.
     *
     * @param nodes the subsystem forest to search, not {@code null}
     * @param names the subsystem names to match, not {@code null}
     * @return the matching nodes, never {@code null}
     */
    private static List<SubsystemNode> collectMatching(List<SubsystemNode> nodes, Set<String> names)
    {
        List<SubsystemNode> result = new ArrayList<>();
        for (SubsystemNode node : nodes)
        {
            if (names.contains(node.name()))
            {
                result.add(node);
            }
            result.addAll(collectMatching(node.children(), names));
        }
        return result;
    }

    /**
     * Collects the names of the currently checked subsystem tree elements.
     *
     * @return the checked subsystem names, never {@code null}
     */
    private List<String> checkedSubsystemNames()
    {
        List<String> names = new ArrayList<>();
        for (Object element : subsystemViewer.getCheckedElements())
        {
            names.add(((SubsystemNode)element).name());
        }
        return names;
    }

    /**
     * Lists the local branch names of the git repository containing {@code projectRoot}, best-effort:
     * any failure (not a repository, IO error) yields an empty list rather than throwing into the UI.
     *
     * @param projectRoot the EDT project location, may be {@code null}
     * @return the local branch names, never {@code null}
     */
    private static List<String> localBranchNames(Path projectRoot)
    {
        if (projectRoot == null)
        {
            return List.of();
        }
        try (Git git = Git.open(projectRoot.toFile()))
        {
            List<String> names = new ArrayList<>();
            for (Ref ref : git.branchList().call())
            {
                names.add(Repository.shortenRefName(ref.getName()));
            }
            return names;
        }
        catch (Exception e) // not a repo / IO - no branch suggestions, free text still works
        {
            return List.of();
        }
    }

    private IProject getProject()
    {
        return Adapters.adapt(getElement(), IProject.class);
    }

    private void findKey()
    {
        Optional<SonarConnection> connection = new SonarConnectionFactory().create();
        if (connection.isEmpty())
        {
            findResultLabel.setText(Messages.IssuesView_Status_NotConfigured);
            return;
        }
        String projectName = getProject().getName();
        Job job = Job.create(Messages.PropertyPage_FindKey, monitor ->
        {
            String key = null;
            String error = null;
            try
            {
                List<ComponentInfo> found = new SonarHttpClient(connection.get()).searchProjects(projectName);
                key = found.isEmpty() ? null : found.get(0).key();
            }
            catch (SonarServerException e)
            {
                error = e.getMessage();
            }
            String foundKey = key;
            String failure = error;
            Display.getDefault().asyncExec(() ->
            {
                if (findResultLabel.isDisposed())
                {
                    return;
                }
                if (failure != null)
                {
                    findResultLabel.setText(NLS.bind(Messages.PreferencePage_TestFailure, failure));
                }
                else if (foundKey == null)
                {
                    findResultLabel.setText(Messages.PropertyPage_FindKeyNoMatch);
                }
                else
                {
                    projectKeyText.setText(foundKey);
                    findResultLabel.setText(""); //$NON-NLS-1$
                }
                findResultLabel.getParent().layout();
            });
        });
        job.setSystem(true);
        job.schedule();
    }

    @Override
    public boolean performOk()
    {
        ProjectBinding binding = new ProjectBinding(projectKeyText.getText().trim(),
            branchText.getText().trim(), prefixText.getText().trim(),
            baseBranchCombo.getText().trim(), checkedSubsystemNames());
        try
        {
            new ProjectBindingStore().save(getProject(), binding);
        }
        catch (BackingStoreException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
            return false;
        }
        return true;
    }
}
