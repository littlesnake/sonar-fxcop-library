/*
 * SonarQube FxCop Library
 * Copyright (C) 2014 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.fxcop;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.ActiveRule;

import javax.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FxCopSensor implements Sensor {

  private static final String CUSTOM_RULE_KEY = "CustomRuleTemplate";
  private static final String CUSTOM_RULE_CHECK_ID_PARAMETER = "CheckId";
  private static final Logger LOG = LoggerFactory.getLogger(FxCopSensor.class);

  private final FxCopConfiguration fxCopConf;
  private final Settings settings;
  private final RulesProfile profile;
  private final FileSystem fs;
  private final ResourcePerspectives perspectives;

  public FxCopSensor(FxCopConfiguration fxCopConf, Settings settings, RulesProfile profile, FileSystem fs, ResourcePerspectives perspectives) {
    this.fxCopConf = fxCopConf;
    this.settings = settings;
    this.profile = profile;
    this.fs = fs;
    this.perspectives = perspectives;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    boolean shouldExecute;

    if (!hasFilesToAnalyze()) {
      shouldExecute = false;
    } else if (profile.getActiveRulesByRepository(fxCopConf.repositoryKey()).isEmpty()) {
      LOG.info("All FxCop rules are disabled, skipping its execution.");
      shouldExecute = false;
    } else {
      shouldExecute = true;
    }

    return shouldExecute;
  }

  private boolean hasFilesToAnalyze() {
    return fs.files(fs.predicates().and(fs.predicates().hasLanguage(fxCopConf.languageKey()), fs.predicates().hasType(Type.MAIN))).iterator().hasNext();
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    analyse(context, new FxCopRulesetWriter(), new FxCopReportParser(), new FxCopExecutor());
  }

  @VisibleForTesting
  void analyse(SensorContext context, FxCopRulesetWriter writer, FxCopReportParser parser, FxCopExecutor executor) {
    fxCopConf.checkProperties(settings);

    File reportFile;
    String reportPath = settings.getString(fxCopConf.reportPathPropertyKey());
    if (reportPath == null) {
      File rulesetFile = new File(fs.workDir(), "fxcop-sonarqube.ruleset");
      writer.write(enabledRuleConfigKeys(), rulesetFile);

      reportFile = new File(fs.workDir(), "fxcop-report.xml");

      executor.execute(settings.getString(fxCopConf.fxCopCmdPropertyKey()), settings.getString(fxCopConf.assemblyPropertyKey()),
        rulesetFile, reportFile, settings.getInt(fxCopConf.timeoutPropertyKey()), settings.getBoolean(fxCopConf.aspnetPropertyKey()),
        splitOnCommas(settings.getString(fxCopConf.directoriesPropertyKey())), splitOnCommas(settings.getString(fxCopConf.referencesPropertyKey())));
    } else {
      LOG.debug("Using the provided FxCop report" + reportPath);
      reportFile = new File(reportPath);
    }
      for (FxCopIssue issue : parser.parse(reportFile)) {
    	File file = null;
        File tempFile = null;
        String outSideSonarQube = null;
        int line = 0;	
        String message = "";

        File projectBaseDirectory = fs.baseDir();
        if(hasFile(issue)) {
            file = new File(new File(issue.path()), issue.file());
            if (!hasLine(issue)) {
            	line = 1; 
            }
            line = issue.line(); 
            message = issue.message();         
          } 
          else {
        	    file =  srchFileInDirectory(projectBaseDirectory, "AssemblyInfo.cs"); 
        	    if (file == null)
        	    	file =  srchFileInDirectory(projectBaseDirectory, ".cs");	
        	    line = 1;
        	    message = issue.message() 
        			+ " {File name may not be correct" 
        			+ ". Search file based on class/assembly name appearing in this message}.";
          }
      InputFile inputFile = fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(file.getAbsolutePath())));
      if (inputFile == null)
      { 
      	outSideSonarQube = issue.file();
      	if (outSideSonarQube != null)
      	{
      		List<File> fileList = srchFiles(projectBaseDirectory, outSideSonarQube);
       		if (fileList.isEmpty())
      		{
      			tempFile = srchFileInDirectory(projectBaseDirectory, "AssemblyInfo.cs");
      			if (tempFile == null)
      			{
      			   tempFile =  srchFileInDirectory(projectBaseDirectory, ".cs");	
      			}

      			inputFile =  fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(tempFile.getAbsolutePath())));
      			message = issue.message() 
      	    			+ " {File name may not be correct" 
      	    			+ ". Search file based on class/assembly name appearing in this message}.";
      		}
      		else
      		{   
      			// to handle case when two or more .cs files have same name but in different directory
      			for (File currentFile : fileList) {
      			  inputFile = fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(currentFile.getAbsolutePath())));
      			  if (inputFile != null) {
      				  break;
      			  }
      			}
      			if (inputFile == null && outSideSonarQube.endsWith(".cs") && !(outSideSonarQube.endsWith(".g.cs")) 
      					&& !(outSideSonarQube.endsWith(".g.i.cs")) && !(outSideSonarQube.endsWith(".Designer.cs")))
      			{
      				inputFile = fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(srchFileInDirectory(projectBaseDirectory, "AssemblyInfo.cs").getAbsolutePath())));
      				if (inputFile == null)
      					inputFile = fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(srchFileInDirectory(projectBaseDirectory, ".cs").getAbsolutePath())));
      				    
      				message = issue.message() 
      		    			+ " {File name may not be correct" 
      		    			+ ". Search file based on class/assembly name appearing in this message}.";
      			}      			
      		}      	
         }
      	 else 
      	 {
      		inputFile = fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(srchFileInDirectory(projectBaseDirectory, "AssemblyInfo.cs").getAbsolutePath())));
      		if (inputFile == null)
      			inputFile = fs.inputFile(fs.predicates().and(fs.predicates().hasType(Type.MAIN), fs.predicates().hasAbsolutePath(srchFileInDirectory(projectBaseDirectory, ".cs").getAbsolutePath())));
      		message = issue.message() 
		    			+ " {File name may not be correct" 
		    			+ ". Search file based on class/assembly name appearing in this message}.";
      	 }
      }
     if (inputFile == null && issue != null && issue.file() != null && (issue.file().endsWith(".g.cs") || issue.file().endsWith(".g.i.cs")
    		 || issue.file().endsWith(".Designer.cs") || issue.file().endsWith(".xaml") ) ){ 
    	logSkippedIssueOutsideOfSonarQube(issue, file);
      } else if (inputFile != null && fxCopConf.languageKey().equals(inputFile.language())) {
        Issuable issuable = perspectives.as(Issuable.class, inputFile);
        if (issuable == null) {
          logSkippedIssueOutsideOfSonarQube(issue, file);
        } else {
          issuable.addIssue(
            issuable.newIssueBuilder()
              .ruleKey(RuleKey.of(fxCopConf.repositoryKey(), ruleKey(issue.ruleConfigKey())))
              .line(line)
              .message(message)
              .build());
        }
      }
    }
  }
    
  public static File srchFileInDirectory(File directoryName, String fileName)
  {
	  File srchedFile = null;
	  for (File srcFile: listFileTree(directoryName)) {
		  if(srcFile.getName().equals(fileName))      {  
			  srchedFile = srcFile; 
			  break;  
		  }
	   }
	  if (srchedFile == null) {
		  for (File srcFile: listFileTree(directoryName)) {			  
	   	      	if (srcFile.getName().endsWith(fileName)) {
	   	    	  srchedFile = srcFile;   		   
	   	    	  break;
		        }
	      }
	  }
	  
	  return srchedFile;
  }
  
  public static List<File> srchFiles(File directoryName, String fileName)
  {
	  List<File> fileList = new ArrayList<File>();
	  for (File srcFile: listFileTree(directoryName))    {
		    if(srcFile.getName().equals(fileName))      {  
			  fileList.add(srcFile); 
		    }
	   }
	  if (fileList.isEmpty()) {
		  for (File srcFile: listFileTree(directoryName)) {			  
	   	      	if (srcFile.getName().endsWith(fileName)) {
	   	      	 fileList.add(srcFile);   		   
	   	    	  break;
		        }
	      }
	  }
	  
	  return fileList;
  }
  
  public static Collection<File> listFileTree(File dir) {
	    Set<File> fileTree = new HashSet<>();
	  	for (File entry : dir.listFiles()) {
	        if (entry.isFile()) fileTree.add(entry);
	        else fileTree.addAll(listFileTree(entry));	    	
	    }
	  	
	    return fileTree;
}

  private static List<String> splitOnCommas(@Nullable String property) {
    if (property == null) {
      return ImmutableList.of();
    } else {
      return ImmutableList.copyOf(Splitter.on(",").trimResults().omitEmptyStrings().split(property));
    }
  }

  private static boolean hasFile(FxCopIssue issue) {
	return issue.path() != null && issue.file() != null;
  }
  
  private static boolean hasLine(FxCopIssue issue) {
	return issue.line() != null;
  }

  private static void logSkippedIssueOutsideOfSonarQube(FxCopIssue issue, File file) {
    logSkippedIssue(issue, "whose file \"" + file.getAbsolutePath() + "\" is not in SonarQube.");
  }

  private static void logSkippedIssue(FxCopIssue issue, String reason) {
    LOG.debug("Skipping the FxCop issue at line " + issue.reportLine() + " " + reason);
  }

  private List<String> enabledRuleConfigKeys() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (ActiveRule activeRule : profile.getActiveRulesByRepository(fxCopConf.repositoryKey())) {
      if (!CUSTOM_RULE_KEY.equals(activeRule.getRuleKey())) {
        String effectiveConfigKey = activeRule.getConfigKey();
        if (effectiveConfigKey == null) {
          effectiveConfigKey = activeRule.getParameter(CUSTOM_RULE_CHECK_ID_PARAMETER);
        }

        builder.add(effectiveConfigKey);
      }
    }
    return builder.build();
  }

  private String ruleKey(String ruleConfigKey) {
    for (ActiveRule activeRule : profile.getActiveRulesByRepository(fxCopConf.repositoryKey())) {
      if (ruleConfigKey.equals(activeRule.getConfigKey()) || ruleConfigKey.equals(activeRule.getParameter(CUSTOM_RULE_CHECK_ID_PARAMETER))) {
        return activeRule.getRuleKey();
      }
    }

    throw new IllegalStateException(
      "Unable to find the rule key corresponding to the rule config key \"" + ruleConfigKey + "\" in repository \"" + fxCopConf.repositoryKey() + "\".");
  }

}
