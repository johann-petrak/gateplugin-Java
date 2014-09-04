package com.jpetrak.gate.java;

import com.jpetrak.gate.java.gui.JavaEditorVR;
import java.net.URL;

import gate.Resource;
import gate.Controller;
import gate.Document;
import gate.Factory;
import gate.FeatureMap;
import gate.Gate;

import gate.creole.AbstractLanguageAnalyser;
import gate.creole.ControllerAwarePR;
import gate.creole.CustomDuplication;
import gate.creole.ResourceInstantiationException;
import gate.creole.metadata.CreoleResource;
import gate.creole.metadata.CreoleParameter;
import gate.creole.metadata.Optional;
import gate.creole.metadata.RunTime;
import gate.util.GateClassLoader;
import gate.util.GateRuntimeException;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;

// NOTE: recompiling a script will re-set the flag for running initPr()
// only re-initializing the PR will re-set the flag for running initAll()


@CreoleResource(
        name = "Java Scripting PR",
        helpURL = "",
        comment = "Use a Java program as a processing resource")
public class JavaScriptingPR
        extends AbstractLanguageAnalyser
        implements ControllerAwarePR, JavaCodeDriven, CustomDuplication {

  // ********* Parameters
  @CreoleParameter(comment = "The URL of the Java program to run",suffixes = ".java")
  public void setJavaProgramUrl(URL surl) {
    javaProgramUrl = surl;
  }

  public URL getJavaProgramUrl() {
    return javaProgramUrl;
  }
  protected URL javaProgramUrl;

  @Optional
  @RunTime
  @CreoleParameter(comment = "The input annotation set", defaultValue = "")
  public void setInputAS(String asname) {
    inputAS = asname;
  }

  public String getInputAS() {
    return inputAS;
  }
  protected String inputAS;

  @Optional
  @RunTime
  @CreoleParameter(comment = "The output annotation set", defaultValue = "")
  public void setOutputAS(String asname) {
    outputAS = asname;
  }

  public String getOutputAS() {
    return outputAS;
  }
  protected String outputAS;

  @Optional
  @RunTime
  @CreoleParameter(comment = "The script parameters", defaultValue = "")
  public void setScriptParams(FeatureMap parms) {
    scriptParams = parms;
  }
  
  @Override
  @Optional
  @RunTime
  @CreoleParameter()
  public void setDocument(Document d) {
    document = d;
  }

  @Optional
  @RunTime
  @CreoleParameter()
  public void setResource1(Resource r) {
    resource1 = r;
  }
  public Resource getResource1() {
    return resource1;
  }
  protected Resource resource1;
  
  @Optional
  @RunTime
  @CreoleParameter()
  public void setResource2(Resource r) {
    resource2 = r;
  }
  public Resource getResource2() {
    return resource2;
  }
  protected Resource resource2;
  
  @Optional
  @RunTime
  @CreoleParameter()
  public void setResource3(Resource r) {
    resource3 = r;
  }
  public Resource getResource3() {
    return resource3;
  }
  protected Resource resource3;
  
  
  public FeatureMap getScriptParams() {
    if (scriptParams == null) {
      scriptParams = Factory.newFeatureMap();
    }
    return scriptParams;
  }
  protected FeatureMap scriptParams;
  GateClassLoader classloader = null;
  Controller controller = null;
  File javaProgramFile = null;
  // this is used by the VR
  public File getJavaProgramFile() { return javaProgramFile; }
  List<String> javaProgramLines = null;
  JavaScripting javaProgramClass = null;

  protected File getPluginDir() {
    URL creoleURL = Gate.getCreoleRegister().get(this.getClass().getName()).getXmlFileUrl();
    File pluginDir = gate.util.Files.fileFromURL(creoleURL).getParentFile();
    return pluginDir;
  }
  String fileProlog = "package javascripting;";
  String fileImport = "import com.jpetrak.gate.java.JavaScripting;";
  String classProlog =
          "public class THECLASSNAME extends JavaScripting {";
  String classEpilog = "}";
  Pattern importPattern = Pattern.compile(
          "\\s*import\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*\\.)*(?:[\\p{L}_$][\\p{L}\\p{N}_$]*|\\*)\\s*;\\s*(?://.*)?");

  protected Object lockForPr;  
  
  // This will try and compile the script. 
  // This is done 
  // = at init() time
  // = at reInit() time
  // = when the "Use"  button is pressed in the VR
  // If the script fails compilation, our script object is set to null
  // and the PR will throw an exception when an attempt is made to run it
  public void tryCompileScript() {
    String javaProgramSource;
    String className;
    if (classloader != null) {
      gate.Gate.getClassLoader().forgetClassLoader(classloader);
    }
    classloader =
            gate.Gate.getClassLoader().getDisposableClassLoader(
            javaProgramUrl.toExternalForm() + System.currentTimeMillis());
    try {
      className = "JavaScriptingClass" + getNextId();
      // need to try and reload and compile (if necessary) the script
      //javaProgramSource = FileUtils.readFileToString(Files.fileFromURL(javaProgramUrl),"UTF-8");
      StringBuilder sb = new StringBuilder();
      List<String> imports = new ArrayList<String>();
      javaProgramLines = new ArrayList<String>();
      List<String> scriptLines = new ArrayList<String>();
      LineIterator it = FileUtils.lineIterator(javaProgramFile, "UTF-8");
      try {
        while (it.hasNext()) {
          String line = it.nextLine();
          if (importPattern.matcher(line).matches()) {
            imports.add(line);
          } else {
            scriptLines.add(line);
          }
        }
      } finally {
        LineIterator.closeQuietly(it);
      }
      // paste the final full source text together
      javaProgramLines.add(fileProlog);
      javaProgramLines.add(fileImport);
      javaProgramLines.addAll(imports);
      javaProgramLines.add(classProlog.replaceAll("THECLASSNAME", className));
      javaProgramLines.addAll(scriptLines);
      javaProgramLines.add(classEpilog);
      for (String line : javaProgramLines) {
        sb.append(line);
        sb.append("\n");
      }
      javaProgramSource = sb.toString();
      //System.out.println("Program Source: " + javaProgramSource);
    } catch (IOException ex) {
      System.err.println("Problem reading program from " + javaProgramUrl);
      ex.printStackTrace(System.err);
      return;
    }
    //System.out.println("(Re-)Compiling program "+getJavaProgramUrl()+" ... ");
    Map<String, String> toCompile = new HashMap<String, String>();
    toCompile.put("javascripting." + className, javaProgramSource);
    try {
      gate.util.Javac.loadClasses(toCompile, classloader);
      javaProgramClass = (JavaScripting) Gate.getClassLoader().
              loadClass("javascripting." + className).newInstance();
      javaProgramClass.globalsForPr = globalsForPr;
      javaProgramClass.lockForPr = lockForPr;
      if(registeredEditorVR != null) {
        registeredEditorVR.setCompilationOk();
      }
      javaProgramClass.resource1 = resource1;
      javaProgramClass.resource2 = resource2;
      javaProgramClass.resource3 = resource3;
      isCompileError = false;
    } catch (Exception ex) {
      System.err.println("Problem compiling JavaScripting Class");
      ex.printStackTrace(System.err);
      if(classloader != null) {
        Gate.getClassLoader().forgetClassLoader(classloader);
        classloader = null;
      }
      isCompileError = true;
      javaProgramClass = null;
      if(registeredEditorVR != null) {
        registeredEditorVR.setCompilationError();
      }
      return;
    }
  }
  
  // We need this so that the VR can determine if the latest compile was
  // an error or ok. This is necessary if the VR gets activated after the
  // compilation.
  public boolean isCompileError;
  
  private JavaEditorVR registeredEditorVR = null;

  public void registerEditorVR(JavaEditorVR vr) {
    registeredEditorVR = vr;
  }
  // TODO: make this atomic so it works better in a multithreaded setting
  private static int idNumber = 0;

  private static synchronized String getNextId() {
    idNumber++;
    return ("" + idNumber);
  }


  @Override
  public Resource init() throws ResourceInstantiationException {
    lockForPr = new Object();
    if (getJavaProgramUrl() == null) {
      throw new ResourceInstantiationException("The javaProgramUrl must not be empty");
    }
    javaProgramFile = gate.util.Files.fileFromURL(getJavaProgramUrl());
    try {
      // just check if we can read the script here ... what we read is not actually 
      // ever used
      String tmp = FileUtils.readFileToString(javaProgramFile, "UTF-8");
    } catch (IOException ex) {
      throw new ResourceInstantiationException("Could not read the java program from " + getJavaProgramUrl(), ex);
    }
    tryCompileScript();
    return this;
  }

  @Override
  public void reInit() throws ResourceInstantiationException {
    //System.out.println("JavaScriptingPR reinitializing ...");
    // We re-set the global initialization indicator so that re-init can be
    // used to test the global init method
    if(javaProgramClass != null) {
      javaProgramClass.cleanupPr();
      javaProgramClass.resetInitAll();
    }
    if(registeredEditorVR != null) {
      registeredEditorVR.setFile(getJavaProgramFile());
    }
    init();
  }

  @Override
  public void cleanup() {
    super.cleanup();
    // make sure the generated class does not hold any references
    if (javaProgramClass != null) {
      javaProgramClass.cleanupPr();
      javaProgramClass.doc = null;
      javaProgramClass.controller = null;
      javaProgramClass.corpus = null;
      javaProgramClass.inputASName = null;
      javaProgramClass.outputASName = null;
      javaProgramClass.inputAS = null;
      javaProgramClass.outputAS = null;
      javaProgramClass.parms = null;
      javaProgramClass.globalsForPr = null;
      javaProgramClass.lockForPr = null;
    }
    if (classloader != null) {
      Gate.getClassLoader().forgetClassLoader(classloader);
      classloader = null;
    }
  }

  @Override
  public void execute() {
    if (javaProgramClass != null) {
      try {
        javaProgramClass.resource1 = getResource1();
        javaProgramClass.resource2 = getResource2();
        javaProgramClass.resource3 = getResource3();
        javaProgramClass.doc = document;
        javaProgramClass.controller = controller;
        javaProgramClass.corpus = corpus;
        javaProgramClass.inputASName = getInputAS();
        javaProgramClass.outputASName = getOutputAS();
        javaProgramClass.inputAS =
                (document != null && getInputAS() != null) ? document.getAnnotations(getInputAS()) : null;
        javaProgramClass.outputAS =
                (document != null && getOutputAS() != null) ? document.getAnnotations(getOutputAS()) : null;
        javaProgramClass.parms = getScriptParams();
        javaProgramClass.callExecute();
        javaProgramClass.doc = null;
        javaProgramClass.inputASName = null;
        javaProgramClass.outputASName = null;
        javaProgramClass.inputAS = null;
        javaProgramClass.outputAS = null;
      } catch (Exception ex) {
        printGeneratedProgram(System.err);
        throw new GateRuntimeException("Could not run program for script "+this.getName(), ex);
      }
    } else {
      throw new GateRuntimeException("Cannot run script, compilation failed: "+getJavaProgramUrl());
    }
  }

  private void printGeneratedProgram(PrintStream stream) {
    int linenr = 0;
    for(String line : javaProgramLines) {
      linenr++;
      stream.println(linenr+" "+line);
    }
  }
  
  
  
  @Override
  public void controllerExecutionStarted(Controller controller) {
    this.controller = controller;
    if (javaProgramClass != null) {
      javaProgramClass.resource1 = getResource1();
      javaProgramClass.resource2 = getResource2();
      javaProgramClass.resource3 = getResource3();
      javaProgramClass.controller = controller;
      try {
        javaProgramClass.controllerStarted();
      } catch (Exception ex) {
        System.err.println("Could not run controlerStarted method for script "+this.getName());
        printGeneratedProgram(System.err);
        ex.printStackTrace(System.err);
      }
    }
  }

  @Override
  public void controllerExecutionFinished(Controller controller) {
    this.controller = controller;
    if (javaProgramClass != null) {
      javaProgramClass.controller = controller;
      try {
        javaProgramClass.controllerFinished();
      } catch (Exception ex) {
        System.err.println("Could not run controlerFinished method for script "+this.getName());
        printGeneratedProgram(System.err);
        ex.printStackTrace(System.err);
      }
      javaProgramClass.controller = null;
      javaProgramClass.corpus = null;
      javaProgramClass.parms = null;
    }
  }

  @Override
  public void controllerExecutionAborted(Controller controller, Throwable throwable) {
    this.controller = controller;
    if (javaProgramClass != null) {
      javaProgramClass.controller = controller;
      try {
        javaProgramClass.controllerAborted(throwable);
      } catch (Exception ex) {
        System.err.println("Could not run controlerAborted method for script "+this.getName());
        printGeneratedProgram(System.err);
        ex.printStackTrace(System.err);
      }
      javaProgramClass.controller = null;
      javaProgramClass.corpus = null;
      javaProgramClass.parms = null;
    }
  }
  // This is how we share global data between the different copies created
  // by custom duplication: each JavaScriptingPR instance will initially 
  // get its initial globalForScript map instance. But when duplicate is 
  // executed, that map instance will be overridden by whatever the first PR
  // instance was. At the point where a new compiled script object is created,
  // the compiled script object's map field will get set to that map.
  protected ConcurrentMap<String, Object> globalsForPr =
          new ConcurrentHashMap<String, Object>();
  
  @Override
  public Resource duplicate(Factory.DuplicationContext dc) throws ResourceInstantiationException {
    JavaScriptingPR res = (JavaScriptingPR) Factory.defaultDuplicate(this, dc);
    // Now give the new instance access to the ScriptGlobal data structure
    res.javaProgramClass.globalsForPr = this.javaProgramClass.globalsForPr;
    res.javaProgramClass.lockForPr = this.javaProgramClass.lockForPr;
    if(res.javaProgramClass != null) {
      res.javaProgramClass.duplicationId = this.javaProgramClass.duplicationId + 1;
    }

    return res;
  }
    
}
