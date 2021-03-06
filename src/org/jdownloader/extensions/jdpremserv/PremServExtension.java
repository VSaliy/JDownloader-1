//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.jdownloader.extensions.jdpremserv;

import jd.Main;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.controlling.JDController;
import jd.event.ControlEvent;
import jd.event.ControlListener;
import jd.plugins.AddonPanel;

import org.jdownloader.extensions.AbstractExtension;
import org.jdownloader.extensions.ExtensionConfigPanel;
import org.jdownloader.extensions.StartException;
import org.jdownloader.extensions.StopException;
import org.jdownloader.extensions.jdpremserv.gui.JDPremServGui;

public class PremServExtension extends AbstractExtension<PremServConfig> implements ControlListener {

    private JDPremServGui                           tab;

    private ExtensionConfigPanel<PremServExtension> configPanel;

    public ExtensionConfigPanel<PremServExtension> getConfigPanel() {
        return configPanel;
    }

    public boolean hasConfigPanel() {
        return true;
    }

    public PremServExtension() throws StartException {
        super(null);

    }

    public void controlEvent(ControlEvent event) {
        switch (event.getEventID()) {
        case ControlEvent.CONTROL_INIT_COMPLETE:
            startServer();
        }
    }

    private void startServer() {
        try {
            JDPremServServer.getInstance().start(getPluginConfig().getIntegerProperty("PORT", 8080));
        } catch (Exception e) {
            logger.severe("Could not start JDPremServer: " + e.getMessage());
        }
    }

    private void stopServer() {
        try {
            JDPremServServer.getInstance().stop();
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }

    private void initGUI() {

        tab = new JDPremServGui(this);

    }

    @Override
    public String getIconKey() {
        return "chat";
    }

    @Override
    protected void stop() throws StopException {
        stopServer();
        JDController.getInstance().removeControlListener(this);
    }

    @Override
    protected void start() throws StartException {
        // this method is called ones after the addon has been loaded

        if (Main.isInitComplete()) startServer();

        JDController.getInstance().addControlListener(this);
    }

    protected void initSettings(ConfigContainer config) {
        config.addEntry(new ConfigEntry(ConfigContainer.TYPE_SPINNER, getPluginConfig(), "PORT", "Server", 1024, 65535, 1).setDefaultValue(8080));

    }

    @Override
    public String getConfigID() {
        return "jdpremserv";
    }

    @Override
    public String getAuthor() {
        return "AppWork";
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public AddonPanel<PremServExtension> getGUI() {
        return null;
    }

    @Override
    protected void initExtension() throws StartException {
        ConfigContainer cc = new ConfigContainer(getName());
        initSettings(cc);
        configPanel = createPanelFromContainer(cc);
    }

}
