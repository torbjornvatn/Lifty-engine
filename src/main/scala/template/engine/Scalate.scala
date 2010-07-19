package template.engine

import org.fusesource.scalate.{TemplateEngine,DefaultRenderContext}
import template.util.{TemplateHelper, FileHelper}
import java.io._
import java.net.{URL, URISyntaxException}
import scala.util.matching.Regex
import net.liftweb.common._
import template.engine.commands.{CommandResult}
import template.util.IOHelper

case class Scalate(template: Template with Create, argumentResults: List[ArgumentResult]) {
  
  val engine = {
    val e = new TemplateEngine
    if (GlobalConfiguration.scalaLibraryPath != "") {
      e.classpath = (GlobalConfiguration.scalaLibraryPath :: 
                          GlobalConfiguration.scalaCompilerPath ::
                          GlobalConfiguration.scalatePath :: Nil).mkString(":")
    }
    e
  }
  
  /**
  * Run scalate on all of the template files the Template specifies
  */
  def run: Box[CommandResult] = { 
    
    val processedFiles = template.files.map( t => processSingleTemplate(t) ).filter{ _ match {
      case(_,true) => true
      case(_,false) => false
    }}.map{ case(templateFile,true) => templateFile}
    template.postRenderAction(argumentResults)
    cleanScalateCache
    
    // pretty printing 
    val header = "Running %s with the following arguments:\n".format(template.name)
    val arguments = "\n%s\n".format(argumentResults.map(arg => arg.argument.name+" = "+arg.value).mkString("\n"))
    val files = "\nResulted in the creation of the following files:\n%s"
      .format(processedFiles.map{ path => 
        "  " + TemplateHelper.replaceVariablesInPath(path.destination,argumentResults)
      }.mkString("\n"))
    
    val notice = template.notice(argumentResults) match {
      case Full(notice) => "\n\nNotice:\n%s\n".format(notice)
      case _ => ""
    }
    
    Full(CommandResult("%s%s%s%s".format(header,arguments,files,notice)))
  }
    
  // The version of Scalate I'm using (1.0 scala 2.7.7) doesn't allow you 
  // change the cache settings. The 2.0 brach does so this can be removed later on
  private def cleanScalateCache: Unit = {
    val scalateBytecodeFolder = new File("bytecode")
    val scalateSourceFolder = new File("source")
    if (scalateSourceFolder.exists) FileHelper.recursiveDelete(scalateSourceFolder)
    if (scalateBytecodeFolder.exists) FileHelper.recursiveDelete(scalateBytecodeFolder)
  }
  
  private def safeToCreateFile(file: File): Boolean = {
    
    def askUser: Boolean = {
      val question = "The file %s exists, do you want to override it? (y/n): ".format(file.getPath)
      IOHelper.requestInput(question) match {
        case "y" => true
        case "n" => false
        case _ => askUser
      }
    }
    
    if (file.exists) askUser else true
  }
  
  /**
  * This will process a single scalate template file and save the file in the appropriate 
  * place
  * 
  * @param  templateFile  The template file to process
  * @return               A tuple with the template file and boolean indicating 
  *                       if it suceeded
  */
  private def processSingleTemplate(templateFile: TemplateFile): (TemplateFile,Boolean) = {
    
    val file = FileHelper.loadFile(templateFile.file)
    val sclateTemplate = engine.load(file.getAbsolutePath)
    val destinationPath = TemplateHelper.replaceVariablesInPath(templateFile.destination,argumentResults)
    val buffer = new StringWriter()
    val context = new DefaultRenderContext(new PrintWriter(buffer))
    addArgumentsToContext(context)
    sclateTemplate.render(context)
        
    try {
      FileHelper.createFolderStructure(destinationPath)
      val currentPath = new File("").getAbsolutePath // TODO: Not sure this is needed.
      val file = new File(currentPath+"/"+destinationPath)
      if (safeToCreateFile(file)) {
          file.createNewFile
          val out = new BufferedWriter(new FileWriter(file));
          out.write(buffer.toString);
          out.close();
          (templateFile, true)
      } else {
        (templateFile, false)
      }
    } catch {
      case e: Exception => {
        println("exception!")
        println("dest: " + destinationPath)
        e.printStackTrace
        (templateFile, false)
      }
    } finally {
      // clean up in case the temp filse was generated.
      file.delete 
    }
  }
      
  // this runs through each of the ArgumentResults and adds them to the template context.
  // repeatable arguments gets added as a list
  private def addArgumentsToContext(context: DefaultRenderContext): Unit = {
    // recursivly run through the list and add any repeatable argument to the
    // context as a list. 
    def addArgs(arg: ArgumentResult, args: List[ArgumentResult]): Unit = {
      val toAdd = args.filter( _.argument.name == arg.argument.name)
      toAdd match {
        case argument :: rest if rest == Nil => {
          // Add any repeatable arugment with value of "" as an empty list
          argument match {
            case empty if empty.argument.isInstanceOf[Repeatable] && empty.value == "" => 
              context.attributes(empty.argument.name) = List[String]()
            case repeatable if repeatable.argument.isInstanceOf[Repeatable] => 
              context.attributes(repeatable.argument.name) = List(repeatable.value)
            case argument => 
              context.attributes(argument.argument.name) = argument.value
          }
        }
        case argument :: rest => {
          val list = argument :: rest
          if (list.forall(_.argument.isInstanceOf[Repeatable])) { //Add repeatable as list
            context.attributes(arg.argument.name) = list.map(_.value)
          } else {
            context.attributes(arg.argument.name) = list.map(_.value).first
          }
        }
        case Nil => // Empty list
      }
      val rest = (args -- toAdd)
      rest match {
        case argument :: rest => addArgs(argument,(argument::rest))
        case Nil => // done.
      }
    }
    val allArgs = argumentResults:::template.fixedValues
    if (allArgs.size > 0) {
      addArgs(allArgs.first,allArgs)
    }
  }
 
}