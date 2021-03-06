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

package jd.controlling;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.logging.Logger;

import jd.config.Configuration;
import jd.config.SubConfiguration;
import jd.controlling.proxy.ProxyController;
import jd.controlling.proxy.ProxyInfo;
import jd.event.ControlEvent;
import jd.gui.UserIO;
import jd.gui.swing.SwingGui;
import jd.gui.swing.components.Balloon;
import jd.http.Browser;
import jd.http.BrowserSettings;
import jd.nutils.Formatter;
import jd.nutils.io.JDIO;
import jd.plugins.Account;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.download.DownloadInterface;
import jd.utils.JDUtilities;

import org.appwork.controlling.StateMachine;
import org.appwork.controlling.StateMachineInterface;
import org.appwork.controlling.StateMonitor;
import org.appwork.utils.Regex;
import org.appwork.utils.net.httpconnection.HTTPProxy;
import org.jdownloader.images.NewTheme;
import org.jdownloader.translate._JDT;

/**
 * In dieser Klasse wird der Download parallel zum Hauptthread gestartet
 * 
 * @author astaldo/JD-Team
 */
public class SingleDownloadController extends Thread implements BrowserSettings, StateMachineInterface {
    public static final String                        WAIT_TIME_ON_CONNECTION_LOSS = "WAIT_TIME_ON_CONNECTION_LOSS";

    private static final Object                       DUPELOCK                     = new Object();

    private boolean                                   aborted                      = false;

    /**
     * Das Plugin, das den aktuellen Download steuert
     */
    private PluginForHost                             currentPlugin;

    private DownloadLink                              downloadLink;

    private LinkStatus                                linkStatus;

    /**
     * Der Logger
     */
    private JDPluginLogger                            logger                       = null;

    private long                                      startTime;

    private Account                                   account                      = null;
    private SingleDownloadControllerHandler           handler                      = null;

    private ProxyInfo                                 proxyInfo                    = null;

    private HTTPProxy                                 httpproxy                    = null;

    private StateMachine                              stateMachine;

    private StateMonitor                              stateMonitor;

    public static final org.appwork.controlling.State IDLE_STATE                   = new org.appwork.controlling.State("IDLE");
    public static final org.appwork.controlling.State RUNNING_STATE                = new org.appwork.controlling.State("RUNNING");
    public static final org.appwork.controlling.State FINAL_STATE                  = new org.appwork.controlling.State("FINAL_STATE");
    static {
        IDLE_STATE.addChildren(RUNNING_STATE);
        RUNNING_STATE.addChildren(FINAL_STATE);
    }

    public SingleDownloadControllerHandler getHandler() {
        return handler;
    }

    public void setHandler(SingleDownloadControllerHandler handler) {
        this.handler = handler;
    }

    /**
     * Erstellt einen Thread zum Start des Downloadvorganges
     * 
     * @param controller
     *            Controller
     * @param dlink
     *            Link, der heruntergeladen werden soll
     */
    public SingleDownloadController(DownloadLink dlink, Account account) {
        this(dlink, account, null);
    }

    public SingleDownloadController(DownloadLink dlink, Account account, ProxyInfo proxy) {
        super("JD-StartDownloads");
        stateMachine = new StateMachine(this, IDLE_STATE, FINAL_STATE);
        stateMonitor = new StateMonitor(stateMachine);
        downloadLink = dlink;
        linkStatus = downloadLink.getLinkStatus();
        /* mark link plugin active */
        linkStatus.setActive(true);
        setPriority(Thread.MIN_PRIORITY);
        downloadLink.setDownloadLinkController(this);
        this.account = account;
        this.proxyInfo = proxy;
        if (proxyInfo != null) {
            /* mark this host active in proxyInfo */
            setCurrentProxy(proxyInfo.getProxy());
            proxyInfo.increaseActiveDownloads(dlink.getHost());
        }
    }

    /**
     * @return the proxyInfo
     */
    public ProxyInfo getProxyInfo() {
        return proxyInfo;
    }

    /**
     * Bricht den Downloadvorgang ab.
     */
    public SingleDownloadController abortDownload() {
        aborted = true;
        DownloadInterface dli = downloadLink.getDownloadInstance();
        if (dli != null) dli.stopDownload();
        interrupt();
        return this;
    }

    private void fireControlEvent(ControlEvent controlEvent) {
        JDUtilities.getController().fireControlEvent(controlEvent);
    }

    private void fireControlEvent(int controlID, Object param) {
        JDUtilities.getController().fireControlEvent(controlID, param);
    }

    public PluginForHost getCurrentPlugin() {
        return currentPlugin;
    }

    public DownloadLink getDownloadLink() {
        return downloadLink;
    }

    private void handlePlugin() {
        try {
            this.startTime = System.currentTimeMillis();
            linkStatus.setStatusText(_JDT._.gui_download_create_connection());
            fireControlEvent(ControlEvent.CONTROL_PLUGIN_ACTIVE, currentPlugin);
            DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
            currentPlugin.init();
            if ((downloadLink.getLinkStatus().getRetryCount()) <= currentPlugin.getMaxRetries()) {
                try {
                    try {
                        currentPlugin.handle(downloadLink, account);
                    } catch (jd.http.Browser.BrowserException e) {
                        /* damit browserexceptions korrekt weitergereicht werden */
                        e.closeConnection();
                        if (e.getException() != null) {
                            throw e.getException();
                        } else {
                            throw e;
                        }
                    } finally {
                        if (proxyInfo != null && !proxyInfo.getProxy().getStatus().equals(HTTPProxy.STATUS.OK)) {
                            /*
                             * disable proxy in case something went wrong with
                             * it
                             */
                            ProxyController.getInstance().setEnabled(proxyInfo, false);
                        }
                    }
                } catch (PluginException e) {
                    e.fillLinkStatus(downloadLink.getLinkStatus());
                    if (e.getLinkStatus() == LinkStatus.ERROR_PLUGIN_DEFECT) logger.info(JDLogger.getStackTrace(e));
                    logger.info(downloadLink.getLinkStatus().getLongErrorMessage());
                } catch (UnknownHostException e) {
                    linkStatus.addStatus(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
                    linkStatus.setErrorMessage(_JDT._.plugins_errors_nointernetconn());
                    linkStatus.setValue(SubConfiguration.getConfig("CONNECTION_PROBLEMS").getGenericProperty("UnknownHostException", 5 * 60 * 1000l));
                } catch (SocketTimeoutException e) {
                    linkStatus.addStatus(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
                    linkStatus.setErrorMessage(_JDT._.plugins_errors_hosteroffline());
                    linkStatus.setValue(SubConfiguration.getConfig("CONNECTION_PROBLEMS").getGenericProperty("SocketTimeoutException", 10 * 60 * 1000l));
                } catch (SocketException e) {
                    linkStatus.addStatus(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
                    linkStatus.setErrorMessage(_JDT._.plugins_errors_disconnect());
                    linkStatus.setValue(SubConfiguration.getConfig("CONNECTION_PROBLEMS").getGenericProperty("SocketException", 5 * 60 * 1000l));
                } catch (IOException e) {
                    linkStatus.addStatus(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
                    linkStatus.setErrorMessage(_JDT._.plugins_errors_hosterproblem());
                    linkStatus.setValue(10 * 60 * 1000l);
                } catch (InterruptedException e) {
                    long rev = downloadLink.getLivePlugin() == null ? -1 : downloadLink.getLivePlugin().getVersion();
                    logger.finest("Hoster Plugin Version: " + rev);
                    linkStatus.addStatus(LinkStatus.ERROR_PLUGIN_DEFECT);
                    linkStatus.setErrorMessage(_JDT._.plugins_errors_error() + JDUtilities.convertExceptionReadable(e));
                } catch (Throwable e) {
                    logger.finest("Hoster Plugin Version: " + downloadLink.getLivePlugin().getVersion());
                    JDLogger.exception(e);
                    linkStatus.addStatus(LinkStatus.ERROR_PLUGIN_DEFECT);
                    linkStatus.setErrorMessage(_JDT._.plugins_errors_error() + JDUtilities.convertExceptionReadable(e));
                }
            } else {
                /* TODO: */
                /*
                 * we assume the plugin to be broken when download failed too
                 * often
                 */
                downloadLink.getLinkStatus().addStatus(LinkStatus.ERROR_PLUGIN_DEFECT);
                downloadLink.getLinkStatus().setErrorMessage("Download failed!");
            }

            if (isAborted() && !linkStatus.isFinished()) {
                linkStatus.setErrorMessage(null);
                linkStatus.setStatus(LinkStatus.TODO);
                return;
            }
            if (linkStatus.isFailed()) {
                logger.warning("\r\nError occured- " + downloadLink.getLinkStatus());
            }
            if (handler != null) {
                /* special handler is used */
                if (handler.handleDownloadLink(downloadLink, account)) return;
            }
            switch (linkStatus.getLatestStatus()) {
            case LinkStatus.ERROR_LOCAL_IO:
                onErrorLocalIO(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_IP_BLOCKED:
                onErrorIPWaittime(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE:
                onErrorDownloadTemporarilyUnavailable(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE:
                onErrorHostTemporarilyUnavailable(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_FILE_NOT_FOUND:
                if (SwingGui.getInstance() != null) Balloon.showIfHidden(_JDT._.ballon_download_error_title(), NewTheme.I().getIcon("false", 32), _JDT._.ballon_download_fnf_message(downloadLink.getName() + " (" + Formatter.formatReadable(downloadLink.getDownloadSize()) + ")"));
                onErrorFileNotFound(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_LINK_IN_PROGRESS:
                onErrorLinkBlock(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_FATAL:
                if (SwingGui.getInstance() != null) Balloon.showIfHidden(_JDT._.ballon_download_error_title(), NewTheme.I().getIcon("false", 32), _JDT._.ballon_download_fatalerror_message(downloadLink.getHost()));
                onErrorFatal(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_CAPTCHA:
                onErrorCaptcha(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_PREMIUM:
                onErrorPremium(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_DOWNLOAD_INCOMPLETE:
                onErrorIncomplete(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_ALREADYEXISTS:
                onErrorFileExists(downloadLink, currentPlugin);
                break;
            case LinkStatus.ERROR_DOWNLOAD_FAILED:
                onErrorChunkloadFailed(downloadLink, currentPlugin);
                if (SwingGui.getInstance() != null) Balloon.showIfHidden(_JDT._.ballon_download_error_title(), NewTheme.I().getIcon("false", 32), _JDT._.ballon_download_failed_message(downloadLink.getName() + " (" + Formatter.formatReadable(downloadLink.getDownloadSize()) + ")"));
                break;
            case LinkStatus.ERROR_PLUGIN_DEFECT:
                onErrorPluginDefect(downloadLink, currentPlugin);
                if (SwingGui.getInstance() != null) Balloon.showIfHidden(_JDT._.ballon_download_error_title(), NewTheme.I().getIcon("false", 32), _JDT._.ballon_download_plugindefect_message(downloadLink.getHost()));
                break;
            case LinkStatus.ERROR_NO_CONNECTION:
            case LinkStatus.ERROR_TIMEOUT_REACHED:
                if (SwingGui.getInstance() != null) Balloon.showIfHidden(_JDT._.ballon_download_error_title(), NewTheme.I().getIcon("false", 32), _JDT._.ballon_download_connectionlost_message(downloadLink.getHost()));
                onErrorNoConnection(downloadLink, currentPlugin);
                break;
            default:
                if (linkStatus.hasStatus(LinkStatus.FINISHED)) {
                    logger.finest("\r\nFinished- " + downloadLink.getLinkStatus());
                    logger.info("\r\nFinished- " + downloadLink.getFileOutput());
                    onDownloadFinishedSuccessFull(downloadLink);
                } else {
                    retry(downloadLink, currentPlugin);
                }
            }
        } catch (Throwable e) {
            logger.severe("Error in Plugin Version: " + downloadLink.getLivePlugin().getVersion());
            JDLogger.exception(e);
        }
    }

    private void onErrorLinkBlock(DownloadLink downloadLink, PluginForHost currentPlugin) {
        if (handler != null) {
            /* special handler is used */
            if (handler.handleDownloadLink(downloadLink, account)) return;
        }
        LinkStatus status = downloadLink.getLinkStatus();
        if (status.hasStatus(LinkStatus.ERROR_ALREADYEXISTS)) {
            onErrorFileExists(downloadLink, currentPlugin);
        } else {
            status.resetWaitTime();
            downloadLink.setEnabled(false);
        }
    }

    private void onErrorPluginDefect(DownloadLink downloadLink2, PluginForHost currentPlugin2) {
        long rev = downloadLink.getLivePlugin() == null ? -1 : downloadLink.getLivePlugin().getVersion();
        logger.warning("The Plugin for " + currentPlugin.getHost() + " seems to be out of date(rev" + rev + "). Please inform the Support-team http://jdownloader.org/support.");
        if (downloadLink2.getLinkStatus().getErrorMessage() != null) logger.warning(downloadLink2.getLinkStatus().getErrorMessage());
        // Dieser Exception deutet meistens auf einen PLuginfehler hin. Deshalb
        // wird in diesem Fall die zuletzt geladene browserseite aufgerufen.
        try {
            logger.finest(currentPlugin2.getBrowser().getRequest().getHttpConnection() + "");
        } catch (Exception e) {
        }
        try {
            logger.finest(currentPlugin2.getBrowser() + "");
        } catch (Exception e) {
        }
        String orgMessage = downloadLink2.getLinkStatus().getErrorMessage();
        downloadLink2.getLinkStatus().setErrorMessage(_JDT._.controller_status_plugindefective() + (orgMessage == null ? "" : " " + orgMessage));
        if (SwingGui.getInstance() != null) downloadLink.requestGuiUpdate();
    }

    /**
     * download aborted by user?
     * 
     * @return
     */
    public boolean isAborted() {
        return aborted;
    }

    private void onDownloadFinishedSuccessFull(DownloadLink downloadLink) {
        if ((System.currentTimeMillis() - startTime) > 30000 && SwingGui.getInstance() != null) Balloon.showIfHidden(_JDT._.ballon_download_successful_title(), NewTheme.I().getIcon("true", 32), _JDT._.ballon_download_successful_message(downloadLink.getName() + " (" + Formatter.formatReadable(downloadLink.getDownloadSize()) + ")"));
        downloadLink.setProperty(DownloadLink.STATIC_OUTPUTFILE, downloadLink.getFileOutput());

        // set all links to disabled that point to the same file location
        // - prerequisite: 'skip link' option selected
        // TODO WORKAROUND FOR NOW.. WILL BE HANDLED BY A MIRROR MANAGER IN THE
        // FUTURE
        if (JSonWrapper.get("DOWNLOAD").getIntegerProperty(Configuration.PARAM_FILE_EXISTS, 1) == 1) {
            ArrayList<DownloadLink> links = DownloadController.getInstance().getAllDownloadLinks();
            for (DownloadLink link : links) {
                if (downloadLink != link && downloadLink.getFileOutput().equals(link.getFileOutput())) {
                    /*
                     * mirror links need this error at the moment for other
                     * things to work correctly
                     */
                    link.getLinkStatus().addStatus(LinkStatus.ERROR_ALREADYEXISTS);
                    link.getLinkStatus().setErrorMessage(_JDT._.controller_status_fileexists_othersource(downloadLink.getHost()));
                    link.setEnabled(false);
                    if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(link);
                }
            }
        }

        if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
        if (JDController.isContainerFile(new File(downloadLink.getFileOutput()))) {
            if (JDUtilities.getConfiguration().getBooleanProperty(Configuration.PARAM_RELOADCONTAINER, true)) {
                JDController.loadContainerFile(new File(downloadLink.getFileOutput()));
            }
        }

    }

    /**
     * Diese Funktion wird aufgerufen wenn ein Download wegen eines
     * captchafehlersabgebrochen wird
     * 
     * @param downloadLink
     * @param plugin2
     * @param step
     */
    private void onErrorCaptcha(DownloadLink downloadLink, PluginForHost plugin) {
        retry(downloadLink, plugin);
    }

    private void retry(DownloadLink downloadLink, PluginForHost plugin) {
        int r;
        if (downloadLink.getLinkStatus().getValue() > 0) {
            downloadLink.getLinkStatus().setStatusText(null);
        }
        if ((r = downloadLink.getLinkStatus().getRetryCount()) <= plugin.getMaxRetries()) {
            downloadLink.getLinkStatus().reset();
            downloadLink.getLinkStatus().setRetryCount(r + 1);
            downloadLink.getLinkStatus().setErrorMessage(null);
            try {
                plugin.sleep(Math.max((int) downloadLink.getLinkStatus().getValue(), 2000), downloadLink);
            } catch (PluginException e) {
                downloadLink.getLinkStatus().setStatusText(null);
                if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
                return;
            }
        } else {
            downloadLink.getLinkStatus().addStatus(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
    }

    private void onErrorChunkloadFailed(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus linkStatus = downloadLink.getLinkStatus();
        if (linkStatus.getErrorMessage() == null) {
            linkStatus.setErrorMessage(_JDT._.plugins_error_downloadfailed());
        }
        if (linkStatus.getValue() != LinkStatus.VALUE_FAILED_HASH) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
            retry(downloadLink, plugin);
        }
    }

    private void onErrorFatal(DownloadLink downloadLink, PluginForHost currentPlugin) {
        if (SwingGui.getInstance() != null) downloadLink.requestGuiUpdate();
    }

    private void onErrorFileExists(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus status = downloadLink.getLinkStatus();
        String[] fileExists = new String[] { _JDT._.system_download_triggerfileexists_overwrite(), _JDT._.system_download_triggerfileexists_skip(), _JDT._.system_download_triggerfileexists_rename() };
        String title = _JDT._.jd_controlling_SingleDownloadController_askexists_title();
        String msg = _JDT._.jd_controlling_SingleDownloadController_askexists(downloadLink.getFileOutput());
        int doit = JSonWrapper.get("DOWNLOAD").getIntegerProperty(Configuration.PARAM_FILE_EXISTS, 1);
        if (doit == 4) {

            // ask
            doit = UserIO.getInstance().requestComboDialog(UserIO.NO_COUNTDOWN, title, msg, fileExists, 0, null, null, null, null);

        }
        if (doit == 3) {
            if (downloadLink.getFilePackage().getIntegerProperty("DO_WHEN_EXISTS", -1) > 0) {

                doit = downloadLink.getFilePackage().getIntegerProperty("DO_WHEN_EXISTS", -1);

                try {
                    UserIO.setCountdownTime(10);
                    doit = UserIO.getInstance().requestComboDialog(0, title, msg, fileExists, doit, null, null, null, null);
                    downloadLink.getFilePackage().setProperty("DO_WHEN_EXISTS", doit);
                } finally {
                    UserIO.setCountdownTime(-1);
                }
            } else {
                // ask
                doit = UserIO.getInstance().requestComboDialog(0, title, msg, fileExists, 0, null, null, null, null);
                downloadLink.getFilePackage().setProperty("DO_WHEN_EXISTS", doit);
            }

        }
        switch (doit) {
        case 1:
            status.setErrorMessage(_JDT._.controller_status_fileexists_skip());
            downloadLink.setEnabled(false);
            break;
        case 2:
            // auto rename
            status.reset();
            File file = new File(downloadLink.getFileOutput());
            String filename = file.getName();
            String extension = JDIO.getFileExtension(file);
            String name = filename.substring(0, filename.length() - extension.length() - 1);
            int copy = 2;
            try {
                String[] num = new Regex(name, "(.*)_(\\d+)").getRow(0);
                copy = Integer.parseInt(num[1]) + 1;
                downloadLink.forceFileName(name + "_" + copy + "." + extension);
                while (new File(downloadLink.getFileOutput()).exists()) {
                    copy++;
                    downloadLink.forceFileName(name + "_" + copy + "." + extension);
                }
            } catch (Exception e) {
                copy = 2;
                downloadLink.forceFileName(name + "_" + copy + "." + extension);
            }

            break;
        default:

            if (new File(downloadLink.getFileOutput()).delete()) {
                status.reset();
            } else {
                status.addStatus(LinkStatus.ERROR_FATAL);
                status.setErrorMessage(_JDT._.controller_status_fileexists_overwritefailed() + downloadLink.getFileOutput());
            }
        }
        if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
    }

    /**
     * Wird aufgerufenw ennd as Plugin einen filenot found Fehler meldet
     * 
     * @param downloadLink
     * @param plugin
     * @param step
     */
    private void onErrorFileNotFound(DownloadLink downloadLink, PluginForHost plugin) {
        logger.severe("File not found :" + downloadLink.getDownloadURL());
        downloadLink.setEnabled(false);
    }

    private void onErrorIncomplete(DownloadLink downloadLink, PluginForHost plugin) {
        retry(downloadLink, plugin);
    }

    private void onErrorNoConnection(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus linkStatus = downloadLink.getLinkStatus();
        logger.severe("Error occurred: No Server connection");
        long milliSeconds = JSonWrapper.get("DOWNLOAD").getIntegerProperty(WAIT_TIME_ON_CONNECTION_LOSS, 5 * 60) * 1000;
        linkStatus.addStatus(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
        linkStatus.setWaitTime(milliSeconds);
        if (linkStatus.getErrorMessage() == null) {
            linkStatus.setErrorMessage(_JDT._.controller_status_connectionproblems());
        }
    }

    /**
     * Fehlerfunktion für einen UNbekannten premiumfehler.
     * Plugin-premium-support wird deaktiviert und link wird erneut versucht
     * 
     * @param downloadLink
     * @param plugin
     * @param step
     */
    private void onErrorPremium(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus linkStatus = downloadLink.getLinkStatus();
        linkStatus.reset();
    }

    private void onErrorLocalIO(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus status = downloadLink.getLinkStatus();
        /*
         * Value<=0 bedeutet das der link dauerhauft deaktiviert bleiben soll.
         * value>0 gibt die zeit an die der link deaktiviert bleiben muss in ms.
         * Der DownloadWatchdoggibt den Link wieder frei ewnn es zeit ist.
         */
        status.setWaitTime(30 * 60 * 1000l);
        downloadLink.setEnabled(false);
    }

    /**
     * TODO: needs max retry here, best would be that plugin know how often
     * retry was done and then can react differently on try x
     */
    /**
     * Wird aufgerufen wenn ein Link kurzzeitig nicht verfügbar ist. ER wird
     * deaktiviert und kann zu einem späteren zeitpunkt wieder aktiviert werden
     * 
     * @param downloadLink
     * @param plugin
     * @param step
     */
    private void onErrorDownloadTemporarilyUnavailable(DownloadLink downloadLink, PluginForHost plugin) {
        logger.warning("Error occurred: Temporarily unavailable: Please wait " + downloadLink.getLinkStatus().getValue() + " ms for a retry");
        LinkStatus status = downloadLink.getLinkStatus();
        if (status.getErrorMessage() == null) status.setErrorMessage(_JDT._.controller_status_tempunavailable());

        /*
         * Value<0 bedeutet das der link dauerhauft deaktiviert bleiben soll.
         * value>0 gibt die zeit an die der link deaktiviert bleiben muss in ms.
         * value==0 macht default 30 mins Der DownloadWatchdoggibt den Link
         * wieder frei ewnn es zeit ist.
         */
        if (status.getValue() > 0) {
            status.setWaitTime(status.getValue());
        } else if (status.getValue() == 0) {
            status.setWaitTime(30 * 60 * 1000l);
        } else {
            status.resetWaitTime();
            downloadLink.setEnabled(false);
        }
        if (status.getValue() >= 0) {
            /* plugin can evaluate retrycount and act differently then */
            downloadLink.getLinkStatus().setRetryCount(downloadLink.getLinkStatus().getRetryCount() + 1);
        }
        if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
    }

    private void onErrorHostTemporarilyUnavailable(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus status = downloadLink.getLinkStatus();
        long milliSeconds = downloadLink.getLinkStatus().getValue();
        if (milliSeconds <= 0) {
            logger.severe(_JDT._.plugins_errors_pluginerror());
            milliSeconds = 3600000l;
        }
        logger.warning("Error occurred: Download from this host is currently not possible: Please wait " + milliSeconds + " ms for a retry");
        status.setWaitTime(milliSeconds);
        if (proxyInfo != null) {
            /* set remaining waittime for host-temp unavailable */
            proxyInfo.setRemainingTempUnavail(plugin.getHost(), milliSeconds);
        }
        if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
    }

    /**
     * Diese Funktion wird aufgerufen wenn Ein Download mit einem Waittimefehler
     * abgebrochen wird
     * 
     * @param downloadLink
     * @param plugin
     * @param step
     */
    private void onErrorIPWaittime(DownloadLink downloadLink, PluginForHost plugin) {
        LinkStatus status = downloadLink.getLinkStatus();
        long milliSeconds = downloadLink.getLinkStatus().getValue();

        if (milliSeconds <= 0) {
            logger.severe(_JDT._.plugins_errors_pluginerror());
            milliSeconds = 3600000l;
        }
        status.setWaitTime(milliSeconds);
        status.setStatusText(null);
        if (proxyInfo != null) {
            /* set remaining waittime for host-temp unavailable */
            proxyInfo.setRemainingIPBlockWaittime(plugin.getHost(), milliSeconds);
        }
        if (SwingGui.getInstance() != null) DownloadController.getInstance().fireDownloadLinkUpdate(downloadLink);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Thread#run()
     */
    // @Override
    @Override
    public void run() {
        try {
            stateMachine.setStatus(RUNNING_STATE);
            /**
             * Das Plugin, das den aktuellen Download steuert
             */
            PluginForHost plugin;
            linkStatus.setStatusText(null);
            linkStatus.setErrorMessage(null);
            linkStatus.resetWaitTime();
            /*
             * we are going to download this link, create new liveplugin
             * instance here
             */
            downloadLink.setLivePlugin(downloadLink.getDefaultPlugin().getWrapper().getNewPluginInstance());
            currentPlugin = plugin = downloadLink.getLivePlugin();
            currentPlugin.setLogger(new JDPluginLogger(downloadLink.getHost() + ":" + downloadLink.getName()));
            logger = currentPlugin.getLogger();
            /*
             * handle is only called in download situation, that why we create a
             * new browser instance here
             */
            currentPlugin.setBrowser(new Browser());
            if (currentPlugin != null) {
                fireControlEvent(new ControlEvent(currentPlugin, ControlEvent.CONTROL_PLUGIN_ACTIVE, this));
                if (downloadLink.getDownloadURL() == null) {
                    downloadLink.getLinkStatus().setStatusText(_JDT._.controller_status_containererror());
                    downloadLink.getLinkStatus().setErrorMessage(_JDT._.controller_status_containererror());
                    downloadLink.setEnabled(false);
                    fireControlEvent(new ControlEvent(currentPlugin, ControlEvent.CONTROL_PLUGIN_INACTIVE, this));
                    return;
                }
                /* check ob Datei existiert oder bereits geladen wird */
                synchronized (DUPELOCK) {
                    /*
                     * dieser sync block dient dazu das immer nur ein link
                     * gestartet wird und dann der dupe check durchgeführt
                     * werden kann
                     */
                    if (DownloadInterface.preDownloadCheckFailed(downloadLink)) {
                        onErrorLinkBlock(downloadLink, currentPlugin);
                        fireControlEvent(new ControlEvent(currentPlugin, ControlEvent.CONTROL_PLUGIN_INACTIVE, this));
                        return;
                    }
                    /*
                     * setinprogress innerhalb des sync damit keine 2 downloads
                     * gleichzeitig in progress übergehen können
                     */
                    linkStatus.setInProgress(true);
                }
                handlePlugin();
                if (isAborted() && !linkStatus.isFinished()) {
                    /* download aborted */
                    logger.clear();
                    logger.info("\r\nDownload stopped- " + downloadLink.getName());
                } else if (linkStatus.isFinished()) {
                    /* error free */
                    logger.clear();
                    logger.finest("\r\nFinished- " + downloadLink.getLinkStatus());
                    logger.info("\r\nFinished- " + downloadLink.getName() + "->" + downloadLink.getFileOutput());
                }
                /* move download log into global log */
                logger.logInto(JDLogger.getLogger());
                plugin.clean();
            }
            if (SwingGui.getInstance() != null) downloadLink.requestGuiUpdate();
        } finally {
            try {
                fireControlEvent(new ControlEvent(currentPlugin, ControlEvent.CONTROL_PLUGIN_INACTIVE, this));
                linkStatus.setInProgress(false);
                /* cleanup the DownloadInterface/Controller references */
                downloadLink.setDownloadLinkController(null);
                downloadLink.setDownloadInstance(null);
                if (currentPlugin != null) {
                    currentPlugin.setBrowser(null);
                }
                if (currentPlugin != null) {
                    /* clear log history for this download */
                    currentPlugin.getLogger().clear();
                }
                downloadLink.setLivePlugin(null);
                if (proxyInfo != null) {
                    if (!proxyInfo.getProxy().getStatus().equals(HTTPProxy.STATUS.OK)) {
                        ProxyController.getInstance().setEnabled(proxyInfo, false);
                    }
                    proxyInfo.decreaseActiveDownloads(downloadLink.getHost());
                }
            } finally {
                stateMachine.setStatus(FINAL_STATE);
                linkStatus.setActive(false);
            }
        }
    }

    public HTTPProxy getCurrentProxy() {
        return httpproxy;
    }

    public void setCurrentProxy(HTTPProxy proxy) {
        this.httpproxy = proxy;
    }

    public void setVerbose(boolean b) {
    }

    public boolean isVerbose() {
        return false;
    }

    public void setDebug(boolean b) {
    }

    public boolean isDebug() {
        return false;
    }

    public void setLogger(Logger logger) {
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * returns StateMonitor for this SingleDownloadController
     * 
     * @return
     */
    public StateMonitor getStateMonitor() {
        return stateMonitor;
    }

    /**
     * throws an UnsupportedOperationException, only needed for the internal
     * StateMachine, use getStateMonitor() instead
     */
    @Deprecated
    public StateMachine getStateMachine() {
        throw new UnsupportedOperationException("statemachine not accessible");
    }

}