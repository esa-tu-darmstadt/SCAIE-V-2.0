package scaiev.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.*;
import org.apache.logging.log4j.core.appender.*;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.*;
import org.apache.logging.log4j.core.config.builder.impl.*;
import org.yaml.snakeyaml.Yaml;
import scaiev.SCAIEV;
import scaiev.backend.BNode;
import scaiev.coreconstr.CoreDatab;
import scaiev.frontend.FNode;
import scaiev.frontend.FrontendNodeException;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVInstr.InstrTag;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;

public class SCAIEVCmd {
  // logging
  protected static Logger logger = null;

  public static HashMap<String, Integer> earliest_operation = new HashMap<String, Integer>();

  // options for cmdline parser
  static Options options = new Options();

  // object and function for help text generation
  private static HelpFormatter helper = new HelpFormatter();
  private static void printHelpAndExit(Options options) {
    helper.printHelp("scaievcmd - generate SCAIE-V SCAL layer and integrate SCAIE-V interface for core", options);
    System.exit(-1);
  };

  // entrypoint
  public static void main(String[] args) throws FrontendNodeException {
    // initialize logging
    // get builder to create new appender
    ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
    // generate appender for stdout writing
    AppenderComponentBuilder appenderBuilder =
        builder.newAppender("Stdout", "CONSOLE").addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
    // set printing layout
    appenderBuilder.add(builder.newLayout("PatternLayout").addAttribute("pattern", "%-5level: %msg%n%throwable"));
    // create the appender and root logger for the defined pattern
    builder.add(appenderBuilder);
    builder.add(builder.newRootLogger(Level.OFF).add(builder.newAppenderRef("Stdout")));
    // initialize logging and generate logger for current class
    Configurator.initialize(builder.build());
    logger = LogManager.getLogger();

    CommandLineParser parser = new DefaultParser();

    // read available cores for help text printing
    CoreDatab coreDatab = new CoreDatab();
    try {
      coreDatab.ReadAvailCores("./Cores");
    } catch (Exception e) {
      System.out.println("Cannot read core descriptions!");
      printHelpAndExit(options);
    }

    options.addOption(Option.builder("c")
                          .longOpt("core")
                          .argName("processor name")
                          .hasArg()
                          .required(true)
                          .desc("RISC-V core to patch. Must be one of: " + coreDatab.GetCoreNames())
                          .build());
    options.addOption(Option.builder("i")
                          .longOpt("isax")
                          .argName("ISAX.yaml")
                          .hasArg()
                          .required(false)
                          .desc("YAML-file describing the ISAX interface as created by Longnail")
                          .build());
    options.addOption(Option.builder("o")
                          .longOpt("outdir")
                          .argName("directory")
                          .hasArg()
                          .required(false)
                          .desc("Directory to generate output-files; isaxes subdirectory will be scanned unless isax parameter is set; "
                                + "will use <core> subdirectory by default, trail path with / to disable")
                          .build());
    options.addOption(Option.builder("h").longOpt("help").required(false).desc("Print this message").build());
    options.addOption(Option.builder("q").longOpt("quiet").required(false).desc("Turn off all messages").build());
    options.addOption(Option.builder("v").longOpt("verbose").required(false).desc("Verbose printing").build());
    options.addOption(Option.builder("vv").longOpt("vverbose").required(false).desc("Print debug information and enable -v").build());

    //////////   collect options   //////////
    Stream<File> isaxYamlFiles = Stream.empty(); // Note: Stream is mostly single-use, then becomes invalid
    String core = "";
    String outputDir = "";
    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);

      // print help if requested
      if (line.hasOption("h")) {
        printHelpAndExit(options);
      }

      core = line.getOptionValue("c");
      String isaxYamlFileName = line.getOptionValue("i");
      // set output directory
      outputDir = (line.hasOption("o") ? line.getOptionValue("o") : "results");
      if (outputDir.endsWith("/"))
        outputDir = outputDir.substring(0, outputDir.length() - 1);
      else
        outputDir += "/" + core;
      if (line.hasOption("i")) {
        isaxYamlFiles = Stream.of(new File(isaxYamlFileName));
      } else {
        // Search for ISAX_*.yaml files in the isaxes subdirectory and one subdirectory below that.
        // Skip subdirectories starting with "." or "skip_".
        isaxYamlFiles = Stream.of(Optional.ofNullable(new File(outputDir + "/isaxes").listFiles()).orElse(new File[0]))
                            .mapMulti((File fileIn, Consumer<File> filesOut) -> {
                              if (fileIn.isDirectory()) {
                                if (!fileIn.getName().startsWith(".") && !fileIn.getName().startsWith("skip_"))
                                  Stream.of(fileIn.listFiles()).forEach(file -> filesOut.accept(file));
                              } else
                                filesOut.accept(fileIn);
                            })
                            .filter(file -> file.isFile() && file.getName().startsWith("ISAX_") && file.getName().endsWith(".yaml"));
      }

      // set verbosity of printing
      Level logLvl = Level.INFO;
      if (line.hasOption("q"))
        logLvl = Level.OFF;
      if (line.hasOption("v"))
        logLvl = Level.DEBUG;
      if (line.hasOption("vv"))
        logLvl = Level.TRACE;
      Configurator.setAllLevels(LogManager.getRootLogger().getName(), logLvl);
    } catch (ParseException exp) {
      // parsing failed - raise error to user
      System.err.println(exp.getMessage());
      printHelpAndExit(options);
    }

    ///////// check options are not empty /////////
    assert core != null && !core.isEmpty() : "No core selected!";
    assert outputDir != null && !outputDir.isEmpty() : "No output directory selected!";

    //////////   invoke scaiev logic for SCAL and CPU RTL generation   //////////
    SCAIEV shim = new SCAIEV();
    {
      String core_ = core; // Good ol' Java
      isaxYamlFiles.forEach((File file) -> { parseYAML(file, core_, shim); });
    }
    shim.DefNewNodes(earliest_operation);
    boolean success = shim.Generate(core, outputDir);

    System.exit(success ? 0 : 1);
  }

  // parse yaml and translate the description to scaiev objects
  //
  private static void parseYAML(File isaxDescription, String core, SCAIEV shim) {
    Yaml yamlSched = new Yaml();
    FNode FNode = shim.FNodes;
    BNode BNode = shim.BNodes;
    boolean addedUserNode = false;
    List<LinkedHashMap> readData;
    try {
      InputStream readFile = new FileInputStream(isaxDescription);
      readData = yamlSched.load(readFile);
      try {
        readFile.close();
      } catch (IOException e) {
      }
    } catch (FileNotFoundException e) {
      logger.error("ISAX yaml file could not be opened");
      printHelpAndExit(options);
      return;
    }
    for (LinkedHashMap readInstr : readData) {
      Set<InstrTag> tags = EnumSet.noneOf(InstrTag.class);
      String instrName = "", mask = "", f3 = "---", f7 = "-------", op = "-------";
      String usernodeName = "";
      boolean is_always = false;
      int usernodeSize = 0, usernodeElements = 0;
      for (Object setting : readInstr.keySet()) {
        if (setting.toString().equals("core")) {
          String targetCoreName = (String)readInstr.get(setting);
          if (!core.equals(targetCoreName)) {
            logger.warn("Core name in the ISAX yaml file does not match the requested core: Got {}, expected {}", targetCoreName, core);
          }
        }

        if (setting.toString().equals("tags")) {
          for (String tagName : (List<String>)readInstr.get(setting)) {
            var tag_opt = InstrTag.fromSerialName(tagName);
            tag_opt.ifPresent(tag -> tags.add(tag));
            if (tag_opt.isEmpty())
              logger.warn("Ignoring unknown tag {}", tagName);
          }
        }
        if (setting.toString().equals("instruction")) {
          instrName = (String)readInstr.get(setting);
          is_always = false;
        }
        if (setting.toString().equals("always")) {
          instrName = (String)readInstr.get(setting);
          is_always = true;
        }
        if (setting.toString().equals("mask")) {
          mask = (String)readInstr.get(setting);
          if (mask.length() != 32) {
            logger.error("The encoding mask should contain 32 characters, no other encoding widths are currently supported. ISAX: {}",
                         instrName);
            return;
          }
          char[] encoding = mask.toCharArray();
          f7 = "";
          f3 = "";
          op = "";
          for (int i = 0; i < 7; i++)
            f7 += encoding[i];
          for (int i = 17; i < 20; i++)
            f3 += encoding[i];
          for (int i = 25; i < 32; i++)
            op += encoding[i];
          for (char c : encoding) {
            if (c != '-' && c != '0' && c != '1') {
              logger.error("Illegal character in instruction encoding string '{}'. Allowed characters are '-', '0', '1'", mask);
              f7 = "";
              f3 = "";
              op = "";
              return;
            }
          }
        }

        if (setting.toString().equals("register")) {
          addedUserNode = true;
          usernodeName = (String)readInstr.get(setting);
        }

        if (setting.toString().equals("width")) {
          usernodeSize = (int)readInstr.get(setting);
        }
        if (setting.toString().equals("elements")) {
          usernodeElements = (int)readInstr.get(setting);
        }

        if (setting.toString().equals("schedule")) {
          if (instrName.isEmpty()) {
            logger.error("An instruction name and mask should be provided before the schedule. Ignoring instruction.");
            break;
          }
          if (f3.isEmpty())
            f3 = "---";
          if (f7.isEmpty())
            f7 = "-------";
          if (op.isEmpty())
            op = "-------";
          logger.debug("Added instr. " + instrName + " with f7 " + f7 + " f3 " + f3 + " op " + op);
          SCAIEVInstr newSCAIEVInstr = shim.addInstr(instrName, f7, f3, op, is_always ? "<always>" : "R"); // TODO R is here by default
          tags.forEach(tag -> newSCAIEVInstr.addTag(tag));
          List<LinkedHashMap> nodes = (List<LinkedHashMap>)readInstr.get(setting);
          boolean decoupled = false;
          boolean dynamic = false;
          for (LinkedHashMap readNode : nodes) {
            String nodeName = "";
            int nodeStage = 0;
            ArrayList<Integer> additionalStages = new ArrayList<Integer>();
            boolean checkIsEarliestMarker = false;
            boolean doNotAddAsNode = false;
            HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
            for (Object nodeSetting : readNode.keySet()) {
              if (nodeSetting.toString().equals("interface")) {
                String newName = (String)readNode.get(nodeSetting);
                if (newName.contains(".")) {
                  String[] splitted = newName.split("\\.");
                  nodeName = splitted[0];
                  if (splitted[1].equals("addr")) {
                    nodeName += "_addr";
                  }
                  checkIsEarliestMarker = true; //.data, .addr suffix
                } else {
                  nodeName = newName;
                  checkIsEarliestMarker = BNode.IsUserBNode(BNode.GetSCAIEVNode(nodeName));
                }
              }
              if (nodeSetting.toString().equals("stage")) {
                if (checkIsEarliestMarker) {
                  if (!op.contentEquals("-------")) {
                    if (earliest_operation.containsKey(
                            nodeName)) { // If there was another entry with an ""earliest"" higher, overwrite it, otherwise, don't
                      int readEarliest = earliest_operation.get(nodeName);
                      if (readEarliest > (int)readNode.get(nodeSetting))
                        earliest_operation.put(nodeName, (int)readNode.get(nodeSetting));
                    } else
                      earliest_operation.put(nodeName, (int)readNode.get(nodeSetting));
                  }
                }
                nodeStage = (int)readNode.get(nodeSetting);
                // if(is_always) // For read/writes without opcode: now set as spawn without decoding; in scaiev mapped on Read stage /WB
                // stage; are direct read/writes 	if( FNode.GetSCAIEVNode(nodeName)==null | (FNode.GetSCAIEVNode(nodeName)!=null &&
                // FNode.GetSCAIEVNode(nodeName).DH)) 		nodeStage = 10000; // immediate write
                checkIsEarliestMarker = false;
              }

              // Multiple stages only supported for read nodes
              if (nodeSetting.toString().equals("stages")) {
                // nodeStage = (int) readNode.get(nodeSetting);
                additionalStages = (ArrayList<Integer>)readNode.get(nodeSetting);
                nodeStage = additionalStages.get(0);
                if (is_always)
                  logger.fatal("No multiple stages supported for always. Node: " + readNode);
              }

              if (nodeSetting.toString().equals("has valid"))
                adjSignals.add(AdjacentNode.validReq);
              if (nodeSetting.toString().equals("has validResp"))
                adjSignals.add(AdjacentNode.validResp);
              if (nodeSetting.toString().equals("has addr"))
                adjSignals.add(AdjacentNode.addr);
              if (nodeSetting.toString().equals("has size"))
                adjSignals.add(AdjacentNode.size);
              if (nodeSetting.toString().equals("is decoupled")) {
                decoupled = true;
                if (dynamic) {
                  logger.warn("An instruction cannot have both static and dynamic spawn operations. All spawn operations of {} will be "
                                  + "treated as dynamic.",
                              instrName);
                }
              }
              if (nodeSetting.toString().equals("is dynamic")) {
                if (decoupled) {
                  logger.warn("An instruction cannot have both non-decoupled dynamic and decoupled operations. All spawn operations of "
                                  + "{} will be treated as dynamic decoupled.",
                              instrName);
                }
                dynamic = true;
                adjSignals.add(AdjacentNode.validReq);
                // adjSignals.add(AdjacentNode.addr);
              }
              if (nodeSetting.toString().equals("is dynamic decoupled")) {
                if (decoupled && !dynamic) {
                  logger.warn("An instruction cannot have both static and dynamic decoupled operations. All operations of {} will be "
                                  + "treated as dynamic.",
                              instrName);
                }
                dynamic = true;
                decoupled = true;
                adjSignals.add(AdjacentNode.validReq);
                // adjSignals.add(AdjacentNode.addr);
              }
            }
            if (!checkIsEarliestMarker && !doNotAddAsNode) {
              if (FNode.IsUserFNode(FNode.GetSCAIEVNode(nodeName)) && (FNode.GetSCAIEVNode(nodeName).elements > 1)) {
                // adjSignals.add(AdjacentNode.addr);
                // adjSignals.add(AdjacentNode.addrReq);
              }
              newSCAIEVInstr.PutSchedNode(BNode.GetSCAIEVNode(nodeName), nodeStage, adjSignals);
              for (int i = 1; i < additionalStages.size(); i++) {
                newSCAIEVInstr.PutSchedNode(FNode.GetSCAIEVNode(nodeName), i);
              }
              logger.trace("INFO. Just added for instr. " + instrName + " nodeName " + nodeName + " nodeStage = " + nodeStage +
                           " hasValid " + adjSignals.contains(AdjacentNode.validReq) + " hasAddr " +
                           adjSignals.contains(AdjacentNode.addr) + " hasValidResp " + adjSignals.contains(AdjacentNode.validResp));
            }
          }
          newSCAIEVInstr.SetAsDynamic(dynamic);
          newSCAIEVInstr.SetAsDecoupled(decoupled);
        }
      }
      if (!usernodeName.isEmpty()) {
        if (FNode != BNode)
          FNode.AddUserNode(usernodeName, usernodeSize, usernodeElements);
        BNode.AddUserNode(usernodeName, usernodeSize, usernodeElements);
      }
    }

    if (addedUserNode)
      logger.debug("User added new nodes. Supported FNodes are: " + shim.FNodes.GetAllFrontendNodes());
  }
}
