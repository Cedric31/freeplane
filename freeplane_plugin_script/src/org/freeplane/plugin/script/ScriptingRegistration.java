/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2008 Joerg Mueller, Daniel Polansky, Christian Foltin, Dimitry Polivaev
 *
 *  This file author is Christian Foltin
 *  It is modified by Dimitry Polivaev in 2008.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.freeplane.plugin.script;

import static org.freeplane.plugin.script.ScriptingConfiguration.CONTEXT_MENU_SCRIPTS_LOCATIONS;
import static org.freeplane.plugin.script.ScriptingConfiguration.MENU_SCRIPTS_LOCATION;

import java.awt.Dimension;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.PrintStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Properties;

import javax.swing.ComboBoxEditor;

import org.apache.commons.lang.StringUtils;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.resources.components.IValidator;
import org.freeplane.core.ui.components.UITools;
import org.freeplane.core.ui.ribbon.RibbonBuilder;
import org.freeplane.core.util.FileUtils;
import org.freeplane.core.util.LogUtils;
import org.freeplane.core.util.TextUtils;
import org.freeplane.features.filter.FilterController;
import org.freeplane.features.map.NodeModel;
import org.freeplane.features.mode.Controller;
import org.freeplane.features.mode.ModeController;
import org.freeplane.features.mode.mindmapmode.MModeController;
import org.freeplane.features.script.IScriptEditorStarter;
import org.freeplane.features.script.IScriptStarter;
import org.freeplane.main.addons.AddOnInstaller;
import org.freeplane.main.addons.AddOnsController;
import org.freeplane.n3.nanoxml.IXMLParser;
import org.freeplane.n3.nanoxml.IXMLReader;
import org.freeplane.n3.nanoxml.StdXMLReader;
import org.freeplane.n3.nanoxml.XMLElement;
import org.freeplane.n3.nanoxml.XMLParserFactory;
import org.freeplane.plugin.script.ScriptEditorPanel.IScriptModel;
import org.freeplane.plugin.script.ScriptEditorPanel.ScriptHolder;
import org.freeplane.plugin.script.addons.ManageAddOnsAction;
import org.freeplane.plugin.script.addons.ManageAddOnsDialog;
import org.freeplane.plugin.script.addons.ScriptAddOnProperties;
import org.freeplane.plugin.script.filter.ScriptConditionController;

class ScriptingRegistration {
	final private class ScriptModel implements IScriptModel {
		final private String mOriginalScript;
		private String mScript;

		public ScriptModel(final String pScript) {
			mScript = pScript;
			mOriginalScript = pScript;
		}

		@Override
        public int addNewScript() {
			return 0;
		}

		@Override
        public ScriptEditorWindowConfigurationStorage decorateDialog(final ScriptEditorPanel pPanel,
		                                                             final String pWindow_preference_storage_property) {
			final String marshalled = ResourceController.getResourceController().getProperty(
			    pWindow_preference_storage_property);
			return ScriptEditorWindowConfigurationStorage.decorateDialog(marshalled, pPanel);
		}

		@Override
        public void endDialog(final boolean pIsCanceled) {
			if (pIsCanceled) {
				mScript = mOriginalScript;
			}
		}

		@Override
        public Object executeScript(final int pIndex, final PrintStream pOutStream, final IFreeplaneScriptErrorHandler pErrorHandler) {
			final ModeController modeController = Controller.getCurrentModeController();
			// the script is completely in the hand of the user -> no security issues.
			final ScriptingPermissions restrictedPermissions = ScriptingPermissions.getPermissiveScriptingPermissions();
			return ScriptingEngine.executeScript(modeController.getMapController().getSelectedNode(), mScript,
			    pErrorHandler, pOutStream, null, restrictedPermissions);
		}

		@Override
        public int getAmountOfScripts() {
			return 1;
		}

		public String getScript() {
			return mScript;
		}

		@Override
        public ScriptHolder getScript(final int pIndex) {
			return new ScriptHolder("Script", mScript);
		}

		@Override
        public boolean isDirty() {
			return !StringUtils.equals(mScript, mOriginalScript);
		}

		@Override
        public void setScript(final int pIndex, final ScriptHolder pScript) {
			mScript = pScript.getScript();
		}

		@Override
        public void storeDialogPositions(final ScriptEditorPanel pPanel,
		                                 final ScriptEditorWindowConfigurationStorage pStorage,
		                                 final String pWindow_preference_storage_property) {
			pStorage.storeDialogPositions(pPanel, pWindow_preference_storage_property);
		}
	}

	final private HashMap<String, Object> mScriptCookies = new HashMap<String, Object>();

	public ScriptingRegistration(ModeController modeController) {
		register(modeController);
	}

	public HashMap<String, Object> getScriptCookies() {
		return mScriptCookies;
	}

	private void register(ModeController modeController) {
		modeController.addExtension(IScriptEditorStarter.class, new IScriptEditorStarter() {
			@Override
            public String startEditor(final String pScriptInput) {
				final ScriptModel scriptModel = new ScriptModel(pScriptInput);
				final ScriptEditorPanel scriptEditorPanel = new ScriptEditorPanel(scriptModel, false);
				scriptEditorPanel.setVisible(true);
				return scriptModel.getScript();
			}

			@Override
            public ComboBoxEditor createComboBoxEditor(Dimension minimumSize) {
	            final ScriptComboBoxEditor scriptComboBoxEditor = new ScriptComboBoxEditor();
	            if(minimumSize != null)
	            	scriptComboBoxEditor.setMinimumSize(minimumSize);
				return scriptComboBoxEditor;
            }
		});
		modeController.addExtension(IScriptStarter.class, new IScriptStarter() {
			@Override
            public void executeScript(NodeModel node, String script) {
				ScriptingEngine.executeScript(node, script);
			}
		});
		registerScriptAddOns();
		if(! modeController.getController().getViewController().isHeadless()){
			registerGuiStuff(modeController);
			ScriptCompiler.compileScriptsOnPath(ScriptResources.getClasspath());
			createUserScriptsDirectory();
			createUserLibDirectory();
		}
		FilterController.getCurrentFilterController().getConditionFactory().addConditionController(100,
			new ScriptConditionController());
	}

    private void registerGuiStuff(ModeController modeController) {
        addPropertiesToOptionPanel();
        modeController.addAction(new ScriptEditor());
        modeController.addAction(new ExecuteScriptForAllNodes());
        modeController.addAction(new ExecuteScriptForSelectionAction());
        final ManageAddOnsAction manageAddOnsAction = new ManageAddOnsAction();
        modeController.addAction(manageAddOnsAction);
        modeController.addExtension(AddOnInstaller.class, new AddOnInstaller() {
        	@Override
            public void install(final URL url) {
        		final ManageAddOnsDialog dialog = manageAddOnsAction.getDialog();
        		dialog.install(url);
        	}
        });
        updateMenus(modeController, new ScriptingConfiguration());
    }

    private void addPropertiesToOptionPanel() {
        final URL preferences = this.getClass().getResource("preferences.xml");
        if (preferences == null)
            throw new RuntimeException("cannot open preferences");
        Controller.getCurrentController().addOptionValidator(new IValidator() {
            @Override
            public ValidationResult validate(Properties properties) {
                final ValidationResult result = new ValidationResult();
                final String readAccessString = properties
                    .getProperty(ScriptingPermissions.RESOURCES_EXECUTE_SCRIPTS_WITHOUT_READ_RESTRICTION);
                final String writeAccessString = properties
                .getProperty(ScriptingPermissions.RESOURCES_EXECUTE_SCRIPTS_WITHOUT_WRITE_RESTRICTION);
                final String classpath = properties.getProperty(ScriptResources.RESOURCES_SCRIPT_CLASSPATH);
                final boolean readAccess = readAccessString != null && Boolean.parseBoolean(readAccessString);
                final boolean writeAccess = writeAccessString != null && Boolean.parseBoolean(writeAccessString);
                final boolean classpathIsSet = classpath != null && classpath.length() > 0;
                if (classpathIsSet && !readAccess) {
                    result.addError(TextUtils.getText("OptionPanel.validate_classpath_needs_readaccess"));
                }
                if (writeAccess && !readAccess) {
                    result.addWarning(TextUtils.getText("OptionPanel.validate_write_without_read"));
                }
                return result;
            }
        });
        final MModeController modeController = (MModeController) Controller.getCurrentModeController();
        modeController.getOptionPanelBuilder().load(preferences);
    }

    private void updateMenus(ModeController modeController, final ScriptingConfiguration configuration) {
        if (UITools.useRibbonsMenu()) {
            final RibbonBuilder menuBuilder = modeController.getUserInputListenerFactory().getMenuBuilder(
                RibbonBuilder.class);
            menuBuilder.registerContributorFactory(MENU_SCRIPTS_LOCATION, new ScriptingRibbonsContributorFactory(
                modeController, configuration));
            menuBuilder.updateRibbon(getClass().getResource("ribbons.xml"));
        }
        else {
            modeController.addMenuContributor(new ScriptingMenuContributor(modeController, configuration,
                MENU_SCRIPTS_LOCATION));
        }
        modeController.addMenuContributor(new ScriptingMenuContributor(modeController, configuration,
            CONTEXT_MENU_SCRIPTS_LOCATIONS));
    }

    private void registerScriptAddOns() {
		File[] addonXmlFiles = AddOnsController.getController().getAddOnsDir().listFiles(new FilenameFilter() {
			@Override
            public boolean accept(File dir, String name) {
				return name.endsWith(".script.xml");
			}
		});
		final IXMLParser parser = XMLParserFactory.createDefaultXMLParser();
		for (File file : addonXmlFiles) {
			BufferedInputStream inputStream = null;
			try {
				inputStream = new BufferedInputStream(new FileInputStream(file));
				final IXMLReader reader = new StdXMLReader(inputStream);
				parser.setReader(reader);
				final ScriptAddOnProperties addOn = new ScriptAddOnProperties((XMLElement) parser.parse());
				addOn.setAddOnPropertiesFile(file);
				AddOnsController.getController().registerInstalledAddOn(addOn);
			}
			catch (final Exception e) {
				LogUtils.warn("error parsing " + file, e);
			}
			finally {
				FileUtils.silentlyClose(inputStream);
			}
		}
	}

	private void createUserScriptsDirectory() {
		final File scriptDir = ScriptResources.getUserScriptsDir();
		if (!scriptDir.exists()) {
			LogUtils.info("creating user scripts directory " + scriptDir);
			scriptDir.mkdirs();
		}
	}

	private void createUserLibDirectory() {
		final File libDir = ScriptResources.getUserLibDir();
		if (!libDir.exists()) {
			LogUtils.info("creating user lib directory " + libDir);
			libDir.mkdirs();
		}
	}
}
