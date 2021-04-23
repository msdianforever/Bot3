package com.vegazsdev.bobobot.commands;

import com.vegazsdev.bobobot.TelegramBot;
import com.vegazsdev.bobobot.core.Command;
import com.vegazsdev.bobobot.db.PrefObj;
import com.vegazsdev.bobobot.utils.Config;
import com.vegazsdev.bobobot.utils.FileTools;
import com.vegazsdev.bobobot.utils.GDrive;
import com.vegazsdev.bobobot.utils.JSONs;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

public class ErfanGSIs extends Command {

    private static final Logger LOGGER = (Logger) LogManager.getLogger(ErfanGSIs.class);

    private static boolean isPorting = false;
    private static ArrayList<GSICmdObj> queue = new ArrayList<>();
    private final String toolPath = "ErfanGSIs/";
    private File[] supportedGSIs9 = new File(toolPath + "roms/9").listFiles(File::isDirectory);
    private File[] supportedGSIs10 = new File(toolPath + "roms/10").listFiles(File::isDirectory);
    private String infoGSI = "";

    public ErfanGSIs() {
        super("jurl2gsi", "Can port gsi");
    }

    @Override
    public void botReply(Update update, TelegramBot bot, PrefObj prefs) {

        String msg = update.getMessage().getText();
        String idAsString = update.getMessage().getFrom().getId().toString();

        if (msg.contains(" allowuser") && Objects.equals(Config.getDefConfig("bot-master"), idAsString)) {
            if (update.getMessage().getReplyToMessage() != null) {
                String userid = update.getMessage().getReplyToMessage().getFrom().getId().toString();
                if (addPortPerm(userid)) {
                    bot.sendMessage(prefs.getString("egsi_allowed").replace("%1", userid), update);
                }
            } else if (msg.contains(" ")) {
                String userid = msg.split(" ")[2];
                if (userid != null && userid.trim().equals("") && addPortPerm(userid)) {
                    bot.sendMessage(prefs.getString("egsi_allowed").replace("%1", userid), update);
                }
            } else {
                bot.sendMessage(prefs.getString("egsi_allow_by_reply").replace("%1", prefs.getHotkey())
                        .replace("%2", this.getAlias()), update);
            }
        } else if (msg.contains(" queue")) {
            if (!queue.isEmpty()) {
                StringBuilder v = new StringBuilder();
                for (int i = 0; i < queue.size(); i++) {
                    v.append("#").append(i + 1).append(": ").append(queue.get(i).getGsi()).append("\n");
                }
                bot.sendMessage(prefs.getString("egsi_current_queue")
                        .replace("%2", v.toString())
                        .replace("%1", String.valueOf(queue.size())), update);
            } else {
                bot.sendMessage(prefs.getString("egsi_no_ports_queue"), update);
            }
        } else if (msg.contains(" cancel")) {

            // cancel by now, maybe work, not tested
            // will exit only on when porting is "active" (when url2gsi.sh is running)
            // after that when port already already finished (eg. uploading, zipping)
            // so this cancel needs more things to fully work

            ProcessBuilder pb;
            pb = new ProcessBuilder("/bin/bash", "-c", "kill -TERM -- -$(ps ax | grep url2GSI.sh | grep -v grep | awk '{print $1;}')");
            try {
                pb.start();
            } catch (IOException ex) {
                //Logger.getLogger(BotTelegram.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else {

            boolean userHasPermissions = userHasPortPermissions(idAsString);

            if (userHasPermissions) {
                GSICmdObj gsiCommand = isCommandValid(update);
                if (gsiCommand != null) {
                    boolean isGSITypeValid = isGSIValid(gsiCommand.getGsi());
                    if (isGSITypeValid) {
                        if (!isPorting) {
                            isPorting = true;
                            createGSI(gsiCommand, bot);
                            while (queue.size() != 0) {
                                GSICmdObj portNow = queue.get(0);
                                queue.remove(0);
                                createGSI(portNow, bot);
                            }
                            isPorting = false;
                        } else {
                            queue.add(gsiCommand);
                            bot.sendMessage(prefs.getString("egsi_added_to_queue"), update);
                        }
                    } else {
                        bot.sendMessage(prefs.getString("egsi_supported_types")
                                .replace("%1",
                                        Arrays.toString(supportedGSIs9).replace(toolPath + "roms/9/", "")
                                                .replace("[", "")
                                                .replace("]", ""))
                                .replace("%2",
                                        Arrays.toString(supportedGSIs10).replace(toolPath + "roms/10/", "")
                                                .replace("[", "")
                                                .replace("]", "")), update);
                    }
                }


            } else {
                // no perm
                bot.sendMessage("No Permissions", update);
            }

        }
    }


    private String try2AvoidCodeInjection(String parameters) {
        try {
            parameters = parameters.replace("&", "")
                    .replace("\\", "").replace(";", "").replace("<", "")
                    .replace(">", "");
        } catch (Exception e) {
            return parameters;
        }
        return parameters;
    }

    private GSICmdObj isCommandValid(Update update) {
        GSICmdObj gsiCmdObj = new GSICmdObj();
        String msg = update.getMessage().getText().replace(Config.getDefConfig("bot-hotkey") + this.getAlias() + " ", "");
        String url;
        String gsi;
        String param;
        try {
            url = msg.split(" ")[1];
            gsiCmdObj.setUrl(url);
            gsi = msg.split(" ")[2];
            gsiCmdObj.setGsi(gsi);
            param = msg.replace(url + " ", "").replace(gsi, "").trim();
            param = try2AvoidCodeInjection(param);
            gsiCmdObj.setParam(param);
            gsiCmdObj.setUpdate(update);
            return gsiCmdObj;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return null;
        }
    }

    private boolean isGSIValid(String gsi) {
        File[] supportedGSIs = ArrayUtils.addAll(supportedGSIs9, supportedGSIs10);
        try {
            String gsi2 = null;
            if (gsi.contains(":")) {
                gsi2 = gsi.split(":")[0];
            }
            for (File supportedGSI : supportedGSIs) {
                if (gsi2 != null) {
                    if (gsi2.equals(supportedGSI.getName())) {
                        return true;
                    }
                } else {
                    if (gsi.equals(supportedGSI.getName())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return false;
        }
        return false;
    }

    private boolean userHasPortPermissions(String idAsString) {
        if (Objects.equals(Config.getDefConfig("bot-master"), idAsString)) {
            return true;
        }
        String portConfigFile = "configs/allowed2port.json";
        return Arrays.asList(Objects.requireNonNull(JSONs.getArrayFromJSON(portConfigFile)).toArray()).contains(idAsString);
    }

    private void createGSI(GSICmdObj gsiCmdObj, TelegramBot bot) {
        Update update = gsiCmdObj.getUpdate();
        ProcessBuilder pb;
        pb = new ProcessBuilder("/bin/bash", "-c",
                "cd " + toolPath + " ; ./url2GSI.sh '" + gsiCmdObj.getUrl() + "' " + gsiCmdObj.getGsi() + " " + gsiCmdObj.getParam()
        );
        boolean success = false;
        StringBuilder fullLogs = new StringBuilder();
        fullLogs.append("Starting process!");
        int id = bot.sendMessage(fullLogs.toString(), update);
        try {
            pb.redirectErrorStream(true);
            Process process = pb.start();
            InputStream is = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            boolean weDontNeedAria2Logs = true;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                line = "`" + line + "`";
                if (line.contains("Downloading firmware to:")) {
                    weDontNeedAria2Logs = false;
                    fullLogs.append("\n").append(line);
                    bot.editMessage(fullLogs.toString(), update, id);
                }
                if (line.contains("Create Temp and out dir") || line.equals("Create Temp and out dir")) {
                    weDontNeedAria2Logs = true;
                }
                if (weDontNeedAria2Logs) {
                    fullLogs.append("\n").append(line);
                    bot.editMessage(fullLogs.toString(), update, id);
                    if (line.contains("GSI done on:")) {
                        success = true;
                    }
                }
            }

            if (success) {

                // gzip files!

                fullLogs.append("\n").append("Creating gzip...");
                bot.editMessage(fullLogs.toString(), update, id);

                String[] gzipFiles = listFilesForFolder(new File("ErfanGSIs" + "/output"));
                for (String gzipFile : gzipFiles) {
                    new FileTools().gzipFile(gzipFile, gzipFile + ".gz");
                }

                // send to google drive

                ArrayList<String> arr = new ArrayList<>();

                try (Stream<Path> paths = Files.walk(Paths.get("ErfanGSIs/output/"))) {
                    paths
                            .filter(Files::isRegularFile)
                            .forEach(a -> {
                                if (!a.toString().endsWith(".img")) {
                                    arr.add(a.toString());
                                }
                                if (a.toString().contains(".txt")) {
                                    infoGSI = a.toString();
                                }
                            });
                } catch (IOException e) {
                    e.printStackTrace();
                }

                fullLogs.append("\n").append("Sending files to Google Drive...");
                bot.editMessage(fullLogs.toString(), update, id);

                GDriveGSI links = new GSIUpload().enviarGSI(gsiCmdObj.getGsi(), arr);

                if (gsiCmdObj.getGsi().contains(":")) {
                    gsiCmdObj.setGsi(gsiCmdObj.getGsi().split(":")[1]);
                }

                StringBuilder generateLinks = new StringBuilder();

                if (links.getA() != null && !links.getA().trim().equals("")) {
                    generateLinks.append("\n*Download A-Only:* [Google Drive](https://drive.google.com/uc?export=download&id=").append(links.getA()).append(")");
                }
                if (links.getAb() != null && !links.getAb().trim().equals("")) {
                    generateLinks.append("\n*Download A/B:* [Google Drive](https://drive.google.com/uc?export=download&id=").append(links.getAb()).append(")");
                }
                if (links.getFolder() != null && !links.getFolder().trim().equals("")) {
                    generateLinks.append("\n*View:* [Google Drive Folder](https://drive.google.com/drive/folders/").append(links.getFolder()).append(")");
                }

                String descGSI = "" + new FileTools().readFile(infoGSI).trim();

                bot.sendMessage("*GSI: " + gsiCmdObj.getGsi() + "*\n\n"
                        + "*Firmware Base: *" + "[URL](" + gsiCmdObj.getUrl() + ")"
                        + "\n\n*Information:*\n`" + descGSI
                        + "`\n" + generateLinks.toString()
                        + "\n\n*Thanks to:* [Contributors List](https://github.com/erfanoabdi/ErfanGSIs/graphs/contributors)"
                        + "\n\n[Ported using ErfanGSIs Tool](https://github.com/erfanoabdi/ErfanGSIs)", update);

                fullLogs.append("\n").append("Finished!");
                bot.editMessage(fullLogs.toString(), update, id);
                FileUtils.deleteDirectory(new File(toolPath + "output"));
            } else {
                throw new Exception("Task finished without generating a valid GSI");
            }
        } catch (Exception ex) {
            LOGGER.error(fullLogs);
        }
    }

    private static String[] listFilesForFolder(final File folder) {
        StringBuilder paths = new StringBuilder();
        for (final File fileEntry : Objects.requireNonNull(folder.listFiles())) {
            if (fileEntry.isDirectory()) {
                listFilesForFolder(fileEntry);
            } else {
                if (fileEntry.getName().contains(".img")) {
                    paths.append(fileEntry.getAbsolutePath()).append("\n");
                }
            }
        }
        return paths.toString().split("\n");
    }

    private boolean addPortPerm(String id) {
        try {
            if (new FileTools().checkFileExistsCurPath("configs/allowed2port.json")) {
                ArrayList z = JSONs.getArrayFromJSON("configs/allowed2port.json");
                if (z != null) {
                    z.add(id);
                }
                JSONs.writeArrayToJSON(z, "configs/allowed2port.json");
            } else {
                ArrayList<String> z = new ArrayList<>();
                z.add(id);
                JSONs.writeArrayToJSON(z, "configs/allowed2port.json");
            }
            return true;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return false;
        }
    }

}

class GSIUpload {

    GDriveGSI enviarGSI(String gsi, ArrayList<String> var) {
        String rand = RandomStringUtils.randomAlphabetic(8);
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm z").format(Calendar.getInstance().getTime());
        try {
            String uid = gsi + " GSI " + date + " " + rand;
            GDrive.createGoogleFolder(null, uid);
            List<com.google.api.services.drive.model.File> googleRootFolders = GDrive.getGoogleRootFolders();
            String folderId = "";
            for (com.google.api.services.drive.model.File folder : googleRootFolders) {
                if (folder.getName().equals(uid)) {
                    folderId = folder.getId();
                    //System.out.println("Folder ID: " + folder.getId() + " --- Name: " + folder.getName());
                }
            }
            for (String sendFile : var) {
                String fileTrim = sendFile.split("output/")[1];
                File uploadFile = new File(sendFile);
                GDrive.createGoogleFile(folderId, "application/gzip", fileTrim, uploadFile);
            }
            String aonly = "";
            String ab = "";
            List<com.google.api.services.drive.model.File> arquivosNaPasta = GDrive.showFiles(folderId);
            for (com.google.api.services.drive.model.File f : arquivosNaPasta) {
                if (!f.getName().contains(".txt")) {
                    if (f.getName().contains("Aonly")) {
                        aonly = f.getId();
                    } else if (f.getName().contains("AB")) {
                        ab = f.getId();
                    }
                }
            }
            GDriveGSI links = new GDriveGSI();
            if (ab != null && !ab.trim().equals("")) {
                links.setAb(ab);
            }
            if (aonly != null && !aonly.trim().equals("")) {
                links.setA(aonly);
            }
            links.setFolder(folderId);
            GDrive.createPublicPermission(folderId);
            return links;
        } catch (Exception e) {
            return null;
        }
    }

}

class GSICmdObj {

    private String url;
    private String gsi;
    private String param;
    private Update update;

    GSICmdObj() {
    }

    String getUrl() {
        return url;
    }

    void setUrl(String url) {
        this.url = url;
    }

    String getGsi() {
        return gsi;
    }

    void setGsi(String gsi) {
        this.gsi = gsi;
    }

    String getParam() {
        return param;
    }

    void setParam(String param) {
        this.param = param;
    }

    public Update getUpdate() {
        return update;
    }

    public void setUpdate(Update update) {
        this.update = update;
    }
}

class GDriveGSI {

    private String ab;
    private String a;
    private String folder;

    GDriveGSI() {
    }

    String getAb() {
        return ab;
    }

    void setAb(String ab) {
        this.ab = ab;
    }

    String getA() {
        return a;
    }

    void setA(String a) {
        this.a = a;
    }

    String getFolder() {
        return folder;
    }

    void setFolder(String folder) {
        this.folder = folder;
    }

package com.vegazsdev.bobobot.commands;

import org.apache.logging.log4j.LogManager;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import com.vegazsdev.bobobot.utils.FileTools;
import java.io.InputStream;
import java.io.Reader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import com.vegazsdev.bobobot.utils.JSONs;
import java.util.Arrays;
import java.io.IOException;
import com.vegazsdev.bobobot.utils.Config;
import com.vegazsdev.bobobot.db.PrefObj;
import com.vegazsdev.bobobot.TelegramBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import java.util.Objects;
import java.io.File;
import java.util.ArrayList;
import org.apache.logging.log4j.core.Logger;
import com.vegazsdev.bobobot.core.Command;

public class ErfanGSIs extends Command
{
    private static final Logger LOGGER;
    private static final ArrayList<GSICmdObj> queue;
    private static boolean isPorting;
    private final String toolPath = "ErfanGSIs/";
    private final File[] supportedGSIs9;
    private final File[] supportedGSIs10;
    private final File[] supportedGSIs11;
    private String infoGSI;
    
    public ErfanGSIs() {
        super("jurl2gsi", "Can port gsi");
        this.supportedGSIs9 = new File("ErfanGSIs/roms/9").listFiles(File::isDirectory);
        this.supportedGSIs10 = new File("ErfanGSIs/roms/10").listFiles(File::isDirectory);
        this.supportedGSIs11 = new File("ErfanGSIs/roms/11").listFiles(File::isDirectory);
        this.infoGSI = "";
    }
    
    private static String[] listFilesForFolder(final File folder) {
        final StringBuilder paths = new StringBuilder();
        for (final File fileEntry : Objects.requireNonNull(folder.listFiles())) {
            if (fileEntry.isDirectory()) {
                listFilesForFolder(fileEntry);
            }
            else if (fileEntry.getName().contains(".img")) {
                paths.append(fileEntry.getAbsolutePath()).append("\n");
            }
        }
        return paths.toString().split("\n");
    }
    
    @Override
    public void botReply(final Update update, final TelegramBot bot, final PrefObj prefs) {
        final String msg = update.getMessage().getText();
        final String idAsString = update.getMessage().getFrom().getId().toString();
        if ((msg.contains(" allowuser") && Objects.equals(Config.getDefConfig("bot-master"), idAsString)) || (msg.contains(" allowuser") && Objects.equals(Config.getDefConfig("bot-submaster"), idAsString))) {
            if (update.getMessage().getReplyToMessage() != null) {
                final String userid = update.getMessage().getReplyToMessage().getFrom().getId().toString();
                if (this.addPortPerm(userid)) {
                    bot.sendReply(prefs.getString("egsi_allowed").replace("%1", userid), update);
                }
            }
            else if (msg.contains(" ")) {
                final String userid = msg.split(" ")[2];
                if (userid != null && userid.trim().equals("") && this.addPortPerm(userid)) {
                    bot.sendReply(prefs.getString("egsi_allowed").replace("%1", userid), update);
                }
            }
            else {
                bot.sendReply(prefs.getString("egsi_allow_by_reply").replace("%1", prefs.getHotkey()).replace("%2", this.getAlias()), update);
            }
        }
        else if (msg.contains(" queue")) {
            if (!ErfanGSIs.queue.isEmpty()) {
                final StringBuilder v = new StringBuilder();
                for (int i = 0; i < ErfanGSIs.queue.size(); ++i) {
                    v.append("#").append(i + 1).append(": ").append(ErfanGSIs.queue.get(i).getGsi()).append("\n");
                }
                bot.sendReply(prefs.getString("egsi_current_queue").replace("%2", v.toString()).replace("%1", String.valueOf(ErfanGSIs.queue.size())), update);
            }
            else {
                bot.sendReply(prefs.getString("egsi_no_ports_queue"), update);
            }
        }
        else if (msg.contains(" cancel")) {
            final ProcessBuilder pb = new ProcessBuilder(new String[] { "/bin/bash", "-c", "kill -TERM -- -$(ps ax | grep url2GSI.sh | grep -v grep | awk '{print $1;}')" });
            try {
                pb.start();
            }
            catch (IOException ex) {}
        }
        else {
            final boolean userHasPermissions = this.userHasPortPermissions(idAsString);
            if (userHasPermissions) {
                final GSICmdObj gsiCommand = this.isCommandValid(update);
                if (gsiCommand != null) {
                    final boolean isGSITypeValid = this.isGSIValid(gsiCommand.getGsi());
                    if (isGSITypeValid) {
                        if (!ErfanGSIs.isPorting) {
                            ErfanGSIs.isPorting = true;
                            this.createGSI(gsiCommand, bot);
                            while (ErfanGSIs.queue.size() != 0) {
                                final GSICmdObj portNow = ErfanGSIs.queue.get(0);
                                ErfanGSIs.queue.remove(0);
                                this.createGSI(portNow, bot);
                            }
                            ErfanGSIs.isPorting = false;
                        }
                        else {
                            ErfanGSIs.queue.add(gsiCommand);
                            bot.sendReply(prefs.getString("egsi_added_to_queue"), update);
                        }
                    }
                    else {
                        bot.sendReply(prefs.getString("egsi_supported_types").replace("%1", Arrays.toString(this.supportedGSIs9).replace("ErfanGSIs/roms/9/", "").replace("[", "").replace("]", "")).replace("%2", Arrays.toString(this.supportedGSIs10).replace("ErfanGSIs/roms/10/", "").replace("[", "").replace("]", "")).replace("%3", Arrays.toString(this.supportedGSIs11).replace("ErfanGSIs/roms/11/", "").replace("[", "").replace("]", "")), update);
                    }
                }
            }
            else {
                bot.sendReply("No Permissions", update);
            }
        }
    }
    
    private String try2AvoidCodeInjection(String parameters) {
        try {
            parameters = parameters.replace("&", "").replace("\\", "").replace(";", "").replace("<", "").replace(">", "").replace("|", "");
        }
        catch (Exception e) {
            return parameters;
        }
        return parameters;
    }
    
    private GSICmdObj isCommandValid(final Update update) {
        final GSICmdObj gsiCmdObj = new GSICmdObj();
        final String msg = update.getMessage().getText().replace(Config.getDefConfig("bot-hotkey") + this.getAlias() + " ", "");
        try {
            final String url = msg.split(" ")[1];
            gsiCmdObj.setUrl(url);
            final String gsi = msg.split(" ")[2];
            gsiCmdObj.setGsi(gsi);
            String param = msg.replace(url + " ", "").replace(gsi, "").trim();
            param = this.try2AvoidCodeInjection(param);
            gsiCmdObj.setParam(param);
            gsiCmdObj.setUpdate(update);
            return gsiCmdObj;
        }
        catch (Exception e) {
            ErfanGSIs.LOGGER.error(e.getMessage(), e);
            return null;
        }
    }
    
    private boolean isGSIValid(final String gsi) {
        return true;
    }
    
    private boolean userHasPortPermissions(final String idAsString) {
        if (Objects.equals(Config.getDefConfig("bot-master"), idAsString) || Objects.equals(Config.getDefConfig("bot-submaster"), idAsString)) {
            return true;
        }
        final String portConfigFile = "configs/allowed2port.json";
        return Arrays.asList(Objects.requireNonNull(JSONs.getArrayFromJSON(portConfigFile)).toArray()).contains(idAsString);
    }
    
    private StringBuilder getModelOfOutput() {
        final StringBuilder generic = new StringBuilder();
        generic.append("Generic");
        final StringBuilder fullLogs = new StringBuilder();
        try {
            final ProcessBuilder pb = new ProcessBuilder(new String[] { "/bin/bash", "-c", "grep -oP \"(?<=^Model: ).*\" -hs \"$(pwd)\"/ErfanGSIs/output/*txt | head -1" });
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            final InputStream is = process.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line;
                if (line.contains("miatoll")) {
                    line = "MiAtoll (9S and others...)";
                }
                if (line.contains("surya")) {
                    line = "Poco X3";
                }
                if (line.contains("raphael")) {
                    line = "Mi 9T Pro";
                }
                if (line.contains("violet")) {
                    line = "Redmi Note 7 Pro";
                }
                if (line.contains("lavender")) {
                    line = "Redmi Note 7";
                }
                if (line.contains("SM6250")) {
                    line = "MiAtoll (9S and others...)";
                }
                if (line.contains("qssi")) {
                    line = "Qualcomm Single System Image (Generic)";
                }
                if (line.contains("mainline")) {
                    line = "AOSP/Pixel (Mainline) Device";
                }
                fullLogs.append(line);
            }
            if (fullLogs.equals("") || fullLogs.equals(null)) {
                return generic;
            }
            return fullLogs;
        }
        catch (Exception e) {
            e.printStackTrace();
            return generic;
        }
    }
    
    private void createGSI(final GSICmdObj gsiCmdObj, final TelegramBot bot) {
        final Update update = gsiCmdObj.getUpdate();
        final ProcessBuilder pb = new ProcessBuilder(new String[] { "/bin/bash", "-c", "cd ErfanGSIs/ ; ./url2GSI.sh '" + gsiCmdObj.getUrl() + "' " + gsiCmdObj.getGsi() + " " + gsiCmdObj.getParam() });
        boolean success = false;
        final StringBuilder fullLogs = new StringBuilder();
        fullLogs.append("Started Build GSI . . . . ");
        final int id = bot.sendReply(fullLogs.toString(), update);
        try {
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            final InputStream is = process.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            boolean weDontNeedAria2Logs = true;
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                line = "`" + line + "`";
                if (line.contains("Downloading firmware to:")) {
                    weDontNeedAria2Logs = false;
                    fullLogs.append("\n").append(line);
                    bot.editMessage(fullLogs.toString(), update, id);
                }
                if (line.contains("Create Temp and out dir")) {
                    weDontNeedAria2Logs = true;
                }
                if (weDontNeedAria2Logs) {
                    fullLogs.append("\n").append(line);
                    bot.editMessage(fullLogs.toString(), update, id);
                    if (!line.contains("GSI done on:")) {
                        continue;
                    }
                    success = true;
                }
            }
            if (!success) {
                throw new Exception("Task finished without generating a valid GSI");
            }
            fullLogs.append("\n").append("Compressing as gzip format . . . . ");
            bot.editMessage(fullLogs.toString(), update, id);
            final String[] listFilesForFolder;
            final String[] gzipFiles = listFilesForFolder = listFilesForFolder(new File("ErfanGSIs/output"));
            for (final String gzipFile : listFilesForFolder) {
                new FileTools().gzipFile(gzipFile, gzipFile + ".gz");
            }
            final ArrayList<String> arr = new ArrayList<String>();
            final AtomicReference<String> aonly = new AtomicReference<String>("");
            final AtomicReference<String> ab = new AtomicReference<String>("");
            try {
                final Stream<Path> paths = Files.walk(Paths.get("ErfanGSIs/output/", new String[0]), new FileVisitOption[0]);
                try {
                    final ArrayList<String> list;
                    final AtomicReference<String> atomicReference;
                    final AtomicReference<String> atomicReference2;
                    paths.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).forEach(a -> {
                        if (a.toString().endsWith(".img.gz")) {
                            list.add(a.toString());
                            if (a.toString().contains("Aonly")) {
                                atomicReference.set(FilenameUtils.getBaseName(a.toString()) + "." + FilenameUtils.getExtension(a.toString()));
                            }
                            else {
                                atomicReference2.set(FilenameUtils.getBaseName(a.toString()) + "." + FilenameUtils.getExtension(a.toString()));
                            }
                        }
                        if (a.toString().contains(".txt")) {
                            this.infoGSI = a.toString();
                        }
                        return;
                    });
                    if (paths != null) {
                        paths.close();
                    }
                }
                catch (Throwable t) {
                    if (paths != null) {
                        try {
                            paths.close();
                        }
                        catch (Throwable exception) {
                            t.addSuppressed(exception);
                        }
                    }
                    throw t;
                }
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            fullLogs.append("\n").append("Uploading to SF re . . . . ");
            bot.editMessage(fullLogs.toString(), update, id);
            String re = new sfUpload().uploadGsi(arr, gsiCmdObj.getGsi());
            re += "/";
            if (gsiCmdObj.getGsi().contains(":")) {
                gsiCmdObj.setGsi(gsiCmdObj.getGsi().split(":")[1]);
                gsiCmdObj.setGsi(gsiCmdObj.getGsi().replace("-", " "));
            }
            final StringBuilder generateLinks = new StringBuilder();
            generateLinks.append("\n*\u25aa\ufe0fDownloads* \n");
            if (!ab.toString().trim().equals("")) {
                generateLinks.append("[AB](https://sourceforge.net/projects/").append(sfsetup.getSfConf("bot-sf-proj")).append("/files/").append(re).append(ab.toString()).append(")");
            }
            if (!ab.toString().trim().equals("") && !aonly.toString().trim().equals("")) {
                generateLinks.append(" | ");
            }
            if (!aonly.toString().trim().equals("")) {
                generateLinks.append("[Aonly](https://sourceforge.net/projects/").append(sfsetup.getSfConf("bot-sf-proj")).append("/files/").append(re).append(aonly.toString()).append(")");
            }
            final String descGSI = "" + new FileTools().readFile(this.infoGSI).trim();
            bot.sendReply("Done! . . . . ", update);
            try {
                if (sfsetup.getSfConf("bot-send-announcement").equals("true")) {
                    try {
                        bot.sendMessage2ID("*Requested " + gsiCmdObj.getGsi() + " GSI*\n*From* " + (Object)this.getModelOfOutput() + "\n\n*\u25aa\ufe0fDetails*\n`" + descGSI + "`\n" + generateLinks.toString() + "\n\n*\u25aa\ufe0fNotes*\n\u2757\ufe0fFile *not found?* wait\n\n*\u25aa\ufe0fCredits*\n[Erfan Abdi](http://github.com/erfanoabdi) | [Sky Yumi](http://github.com/durasame) | [Sheikh Adnan](http://github.com/ElytrA8) | [Jamie](http://github.com/JamieHoSzeYui) | [SpooderSar](https://t.me/spaghettiNmeatballs) | [Vega](http://github.com/VegaBobo) | [Nippon](https://t.me/nnippon)\n\n*\u25aa\ufe0fJoin*\n[Channel](https://t.me/kangergsi) | [Group](https://t.me/kangergsi_support)", Long.parseLong(sfsetup.getSfConf("bot-announcement-id")));
                    }
                    catch (Exception e2) {
                        ErfanGSIs.LOGGER.error("bot-announcement-id looks wrong or not set");
                    }
                }
            }
            catch (Exception e2) {
                ErfanGSIs.LOGGER.warn("bot-send-announcement is not set");
            }
            fullLogs.append("\n").append("Finished!");
            bot.editMessage(fullLogs.toString(), update, id);
            FileUtils.deleteDirectory(new File("ErfanGSIs/output"));
        }
        catch (Exception ex) {
            ErfanGSIs.LOGGER.error(fullLogs);
        }
    }
    
    private boolean addPortPerm(final String id) {
        try {
            new FileTools();
            if (FileTools.checkFileExistsCurPath("configs/allowed2port.json")) {
                final ArrayList z = JSONs.getArrayFromJSON("configs/allowed2port.json");
                if (z != null) {
                    z.add(id);
                }
                JSONs.writeArrayToJSON(z, "configs/allowed2port.json");
            }
            else {
                final ArrayList<String> z2 = new ArrayList<String>();
                z2.add(id);
                JSONs.writeArrayToJSON(z2, "configs/allowed2port.json");
            }
            return true;
        }
        catch (Exception e) {
            ErfanGSIs.LOGGER.error(e.getMessage(), e);
            return false;
        }
    }
    
    static {
        LOGGER = (Logger)LogManager.getLogger(ErfanGSIs.class);
        queue = new ArrayList<GSICmdObj>();
        ErfanGSIs.isPorting = false;
    }
}
