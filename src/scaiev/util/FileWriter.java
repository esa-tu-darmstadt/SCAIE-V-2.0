package scaiev.util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * Class for writing files.
 */
public class FileWriter {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  private static class FileUpdateInfo {
    /** LinkedHashMap<Grep,ToWrite>>, order is important so that declarations of new signals will be before these are used in assigns */
    public LinkedHashMap<ToWrite, String> updates = new LinkedHashMap<ToWrite, String>();
    /** If set, treat the input file as empty, regardless of whether an actual input file exists */
    public boolean clear = false;
  }
  /** key: file relative path */
  private HashMap<String, FileUpdateInfo> update_core = new HashMap<String, FileUpdateInfo>();
  public String tab = "    ";
  public int nrTabs = 0;
  private String base_path = "";
  public FileWriter(String base_path) { this.base_path = base_path; }

  /**
   * Utility function to send each update to a consumer function (in order). The consumer is allowed to manipulate the ToWrite object.
   * @param consumer consumer function to be called with each update's ToWrite object and grep string.
   */
  public void ConsumeUpdates(BiConsumer<ToWrite, String> consumer) {
    for (FileUpdateInfo updateInfo : update_core.values()) {
      for (Entry<ToWrite, String> entry : updateInfo.updates.entrySet()) {
        consumer.accept(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Adds a text insertion update to be applied later when writing files.
   *
   * @param file The relative path to the file to update. The path string should be equal for all updates that target the same file.
   * @param grep Portion of the line to match against. Before or after each matched line, {@link ToWrite#text} will be inserted (depending
   *     on other properties of add_text).
   * @param add_text The ToWrite descriptor object that specifies the text to insert as well as other properties that control matching and
   *     insertion.
   *
   * @see FileWriter#ReplaceContent(String, String, ToWrite)
   * @see scaiev.util.ToWrite#ToWrite(String, boolean, boolean, String, boolean, String)
   */
  public void UpdateContent(String file, String grep, ToWrite add_text) {
    add_text.AllignText(tab.repeat(nrTabs));
    UpdateCorePut(file, grep, add_text);
  }

  /**
   * Adds a text insertion update to be applied later when writing files. This is mostly intended for new files.
   * For more control over the insertion, see {@link FileWriter#UpdateContent(String, String, ToWrite)}.
   *
   * Matches against a line with " ", which will be automatically added when creating new files.
   *
   * @param file The relative path to the file to update. The path string should be equal for all updates that target the same file.
   * @param text The text to insert; use "\n" line breaks for multi-line text. The final line break is added implicitly.
   */
  public void UpdateContent(String file, String text) { // for files which do not grp/replace, but you just add text in a new file
    String grep = " ";
    ToWrite add_text = new ToWrite(text, false, ""); // new file => no module => inmodule is true and before is false
    add_text.AllignText(tab.repeat(nrTabs));
    UpdateCorePut(file, grep, add_text);
  }

  /**
   * Adds a text replacement update to be applied later when writing files.
   *
   * @param file The relative path to the file to update. The path string should be equal for all updates that target the same file.
   * @param grep Portion of the line to match against. Each matched line will be replaced by {@link ToWrite#text} (depending on other
   *     properties of add_text).
   * @param add_text The ToWrite descriptor object that specifies the text to insert as well as other properties that control matching and
   *     insertion.
   *
   * @see FileWriter#UpdateContent(String, String, ToWrite)
   * @see scaiev.util.ToWrite#ToWrite(String, boolean, boolean, String, boolean, String)
   */
  public void ReplaceContent(String file, String grep, ToWrite add_text) {
    add_text.replace = true;
    add_text.AllignText(tab.repeat(nrTabs));
    UpdateCorePut(file, grep, add_text);
  }

  /**
   * Adds a file name to the internal tracking set.
   * If no update is registered for that file, the original file will be copied to the destination directory (if not in-place).
   *
   * @param file The relative path to the file to update. Any update targeting the same file should have an equal path string.
   * @param clear If set, treat the input file as empty, regardless of whether an actual input file exists
   */
  public void AddFile(String file, boolean clear) { update_core.computeIfAbsent(file, file_ -> new FileUpdateInfo()).clear = clear; }

  private void UpdateCorePut(String file, String grep, ToWrite add_text) {
    update_core.computeIfAbsent(file, file_ -> new FileUpdateInfo()).updates.put(add_text, grep);
  }

  /**
   * Writes all files for which an update has been registered with this FileWriter. Files will be read from the current directory.
   *
   * @param langModule The language's equivalent to the Verilog 'module' keyword.
   * @param langEndmodule The language's equivalent to the Verilog 'endmodule' keyword.
   * @param out_path Base output directory. If set to null, the current directory will be used (for in-place update).
   */
  public void WriteFiles(String langModule, String langEndmodule, String out_path) {
    for (String key : update_core.keySet()) {
      WriteFile(update_core.get(key), key, langModule, langEndmodule, out_path);
    }
  }

  private void WriteFile(FileUpdateInfo updateInfo, String file, String langModule, String langEndmodule, String out_path) {
    // create output path if necessary
    (new File(Paths.get(out_path, file).getParent().toString())).mkdirs();

    logger.info("Updating " + file);
    File inFile = new File(base_path, file);
    boolean inplace = (out_path == null || out_path.equals(base_path));
    File outFile = inplace ? new File(base_path, "tempConfig.tmp") : new File(out_path, file);

    boolean empty_inFile = !inFile.exists() || updateInfo.clear;

    BufferedReader in = null;
    PrintWriter out = null;
    // input
    try {
      // If input file does not exist or should be ignored, use a stream with a whitespace line to be found by grep
      InputStream fis = empty_inFile ? new ByteArrayInputStream((" " + System.lineSeparator()).getBytes()) : new FileInputStream(inFile);
      in = new BufferedReader(new InputStreamReader(fis));

      // output
      FileOutputStream fos = new FileOutputStream(outFile);
      out = new PrintWriter(fos);
      String currentLine;

      while ((currentLine = in.readLine()) != null) {
        boolean replace = false;
        ArrayList<String> before = new ArrayList<String>();
        ArrayList<String> after = new ArrayList<String>();
        // future optimization: make a map with strings to be inserted. Each time a string was inserted, make valid (take care if strings
        // must be inserted multiple times. Stop searching if all strings inserted.
        for (ToWrite key_text : updateInfo.updates.keySet()) {
          if (currentLine.contains(langModule) && !currentLine.contains(langEndmodule) && !key_text.in_module.contentEquals("")) {
            if (currentLine.contains(" " + key_text.in_module) || currentLine.contains(key_text.in_module + "(") ||
                currentLine.contains(" " + key_text.in_module + "\n") ||
                currentLine.contains(" " + key_text.in_module +
                                     ";")) { // space to avoid grep stuff like searching for pico and finding picorv32_axi
              key_text.found_module = true;
            } else
              key_text.found_module = false;
          }

          if (key_text.prereq)
            if (currentLine.contains(key_text.prereq_text) && (key_text.found_module)) {
              key_text.prereq_val = true;
            }

          final String grep = updateInfo.updates.get(key_text);
          if (key_text.text != "" && currentLine.contains(grep) && key_text.prereq_val && (key_text.found_module)) {

            key_text.prereq_val = false; // that's why no ""|| !key_text.prereq)"" in line above
            char[] chars = currentLine.toCharArray();
            /*
            char first_letter = ' ';
            for(int i=0;i<chars.length;i++) {
                    if(!Character.isSpaceChar(chars[i]) && !Character.isWhitespace(chars[i])) {
                                    first_letter = chars[i];
                                    break;
                    }
            }*/
            int index = currentLine.indexOf(currentLine.trim());
            char first_letter = currentLine.charAt(index);
            // char first_letter = insert.get(key_text).toCharArray()[0];	// was before
            String[] arrOfStr;
            if (first_letter == ')')
              arrOfStr = currentLine.split("\\)", 2);
            else if (first_letter == '(')
              arrOfStr = currentLine.split("\\(", 2);
            else
              arrOfStr = currentLine.split(Character.toString(first_letter), 2);
            if (key_text.before) {
              before.add(arrOfStr[0] + key_text.text.replaceAll("\n", "\n" + arrOfStr[0]));
            } else
              after.add(arrOfStr[0] + key_text.text.replaceAll("\n", "\n" + arrOfStr[0]));
            if (key_text.replace) // if any of the new textx actually wants to replace this line
              replace = true;
          }
          if (currentLine.contains(langEndmodule) && !key_text.in_module.contentEquals(""))
            key_text.found_module = false;
          // If interface already exists flag err and exit
        }
        for (String text : before)
          out.println(text);
        if (!replace)
          out.println(currentLine);
        for (String text : after)
          out.println(text);
      }

      out.flush();
      out.close();
      in.close();

      if (inplace) {
        inFile.delete();
        outFile.renameTo(inFile);
      }
    } catch (FileNotFoundException e) {
      logger.fatal("File " + file + " could not be found");
      System.exit(-1);
    } catch (IOException e) {
      logger.fatal("Error reading file " + file);
      System.exit(-1);
    }
  }
}
