package template.engine

import net.liftweb.common.{Box, Empty, Failure, Full}
import template.util.{BoxUtil, TemplateHelper}
import java.io.{File}
import template.engine.commands.{CommandResult}
import template.util.IOHelper

case class TemplateFile(file: String, destination: String)

trait Template {
    
  /**
  * The name of the template. This is used to figure out which template the 
  * user is trying to invoke when writig processorName create <templateName>.
  * 
  * @param  name  The name of the template
  * @return       The name of the template
  */
  def name: String
  
  /**
  * This is the list of arguments that the template needs. 
  * 
  * @param  arguments The list of arguments
  * @return           The list of arguments
  */
  def arguments: List[Argument]
  
  /**
  * Override this to provide a message for the user once the template has sucessfully
  * been processed.
  * 
  * @return  The notice to prnt
  */
  def notice(args: List[ArgumentResult]): Box[String] = Empty
  
  /**
  * Provides a description of the template. This will get used to help the users
  * know what they're creating.
  * 
  * @return A description of the template
  */
  def description: String
  
  /**
  * The is the list of files that you want your template to process once invoked
  * 
  * @param  files A list of TemplateFile
  * @return       The list of TemplateFile
  */
  def files: List[TemplateFile]
  
  /**
  * Override this method if you want your template to depend on other templates.
  * The order of the templates is important. If there are any conflicts with the
  * files declared in the templates the conflicting files in the first named template 
  * will get used
  * 
  * @return   The list of templates the template depends on
  */
  def dependencies = List[Template]() 
  
  /**
  * I really can't remember why I have this method. Sorry.
  */
  def fixedValues = List[ArgumentResult]()

  /**
  * This method is invoked before trying to process all the files returned by the
  * files method. Override this method if you have anything you want to do before
  * the files are being processed.
  * 
  * @param  arguments The list of arguments handed to the template by the user
  */
  def preRenderAction(arguments: List[ArgumentResult]): Unit = {}
  
  /**
  * This method is invoked after the template files have been processed. Override
  * this method if you want to do something after the files have been provessed.
  * 
  * @param  arguments well isn't it obvious
  */
  def postRenderAction(arguments: List[ArgumentResult]): Unit = {}

  /**
  * Call this method if you want the template to process it's files. 
  * 
  * @param  operation     The operation to run on the processer ie. create or delete
  * @param  argumentsList The arguments you want to use under the processing of the 
                          template.
  * @return               A CommandResult noting if it went well or not.
  */
  def process(operation: String, argumentsList: List[String]): Box[CommandResult] = {
    operation match {
      case "create" if supportsOperation("create") => this.asInstanceOf[Create].create(argumentsList)
      case "delete" if supportsOperation("delete") => this.asInstanceOf[Delete].delete(argumentsList)
      case _ => Failure("Bollocks!")
    }
  }
    
  /**
  * Gets all of the files declared in this tempalte and each of it's dependencies
  * IMPORTANT: If there are any duplicated templates (i.e. files that have the same
  *            output path) the first in the list will be used. 
  *
  * @return The files of this template and of each dependency (if any)
  */
  def getAllFiles: List[TemplateFile] = {
    
    // removes any duplicate template files from the list
    def filterDuplicates(list: List[TemplateFile]): List[TemplateFile] = {
      // checks if a list of template files contains a template file
      // with the same destination as templ
      def contains(templatesFiles: List[TemplateFile], 
                            templ: TemplateFile): Boolean = {
        templatesFiles.forall( _.destination != templ.destination )
      }
      var cleanList = List[TemplateFile]()
      list.foreach{ item => 
        if (!contains(cleanList,item)) cleanList ::= item
      }
      cleanList
    }
    
    files ::: (dependencies.flatMap(_.files))
    
  }
  
  /**
  * Checks if the template declares any dependencies.
  * 
  * @return true if the templates has any dependencies
  */
  def hasDependencies: Boolean = dependencies.size > 0
    
  /**
  * Takes a list of argument formatted strings (i.e. name=value or just value) and returns a boxed list of
  * ArgumentResults. The actual parsing of the values is done by each subclass of Argument
  * 
  * @param  argumentsList List of argumenst to parse
  * @return A box containing a list of ArgumentResults.
  */
  def parseArguments(argumentsList: List[String]): Box[List[ArgumentResult]] = {
    val regxp = """\w+=\w+""".r
    argumentsList.forall(!regxp.findFirstIn(_).isEmpty) match {
      case true => parseNamedArguments(argumentsList)
      case false => parseIndexedArguments(addUnderscores(argumentsList))
    }
  }
  
  /**
  * Takes a list of argument strings and maps them to ArgumentResuts in the order typed
  * 
  * @param  argumentsList The list of argument strings to parse
  * @return               A box containing a list of ArgumentResults.
  */
  def parseIndexedArguments(argumentsList: List[String]): Box[List[ArgumentResult]] = {
    
    val isRepeatable: String => Boolean = _.contains(",") 
    var argumentResults: List[Box[ArgumentResult]] = Nil
        
    for (i <- 0 to arguments.size-1) {
      val arg = arguments(i) 
      val value = argumentsList(i)
      argumentResults :::= (arg match {
        case arg: Default if value == "_" => arg.default match {
          case Full(str) => Full(ArgumentResult(arg,str)) :: Nil
          case Empty => 
            val requestMsg = "No default value found for %s. Please enter a value: ".format(this.name)
            Full(ArgumentResult(arg,IOHelper.requestInput(requestMsg))) :: Nil
          case Failure(msg,_,_) => Failure(msg) :: Nil
        }
        case arg: Optional if value == "_" => Full(ArgumentResult(arg,"")) :: Nil
        case arg: Argument if value == "_" => Failure("%s doesn't have a default value".format(arg.name)) :: Nil
        case arg: Repeatable if isRepeatable(value) => value.split(",").map( v => Full(ArgumentResult(arg,v))).toList
        case arg: Argument => Full(ArgumentResult(arg,value)) :: Nil
      })
    }
    BoxUtil.containsAnyFailures(argumentResults) match {
      case true => (BoxUtil.collapseFailures(argumentResults))
      case false => Full(argumentResults.map(_.open_!).reverse) // it's okay, I allready checked!
    }
  }
  
  /**
  * Takes a list of arguemnt strings and maps them to ArgumentResults. The strings can be 
  * in any order but should be formattet like this argumentName=value. 
  * 
  * @param  argumentsList The list of argument strings to parse
  * @return               A box containing a list of ArgumentResults.
  */
  def parseNamedArguments(argumentsList: List[String]): Box[List[ArgumentResult]] = {
    val listOfBoxes = arguments.map( _.parseList(argumentsList))
    BoxUtil.containsAnyFailures(listOfBoxes) match {
      case true => 
        Failure(listOfBoxes.filter( _.isInstanceOf[Failure]).map(_.asInstanceOf[Failure].msg).mkString("\n"))
      case false => 
        Full(listOfBoxes.filter(_.isInstanceOf[Full[_]]).flatMap(_.open_!))
    }
  }
  
  override def toString: String = {
    val name =        "  Name:          %s".format(this.name)
    val arguments =   "  Arguments:     %s".format(this.arguments.mkString(","))
    val description = "  Description:   %s".format(this.description)
    (name :: arguments :: description :: Nil).mkString("\n")
  }
    
  protected def deleteFiles(argumentResults :List[ArgumentResult]): Box[CommandResult] = {
    val files = this.files.map( path => TemplateHelper.replaceVariablesInPath(path.destination, argumentResults))
    val result = files.map { path => 
      val file = new File(path)
      "Deleted: %s : %s".format(path,file.delete.toString)
    }
    Full(CommandResult(result.mkString("\n")))
  }
  
  /**
  * Replaces "" with _ and adds _ for each missing (non-optional) argument.
  * 
  * @param  args  List of arguments to convert
  * @return       A list of argument strings
  */
  private def addUnderscores(args: List[String]): List[String] = {
    args.map( str => if(str.matches("")) "_" else str ) ::: (for (i <- 0 to arguments.size - args.size-1) yield { "_" }).toList 
  }
  
  /**
  * Checks if the template supports the operation.
  * 
  * @param  operation The operation to run on the template ie. create/delete
  * @return           True if it does, otherwise false.
  */
  private def supportsOperation(operation: String): Boolean = operation match {
    case "create" => this.isInstanceOf[Create]
    case "delete" => this.isInstanceOf[Delete] 
    case _ => false
  }
}