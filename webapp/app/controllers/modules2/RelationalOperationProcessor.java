package controllers.modules2;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.jruby.exceptions.RaiseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctc.wstx.util.ExceptionUtil;

import play.mvc.results.BadRequest;
import controllers.modules2.framework.TSRelational;
import controllers.modules2.framework.VisitorInfo;
import controllers.modules2.framework.procs.ProcessorSetup;
import controllers.modules2.framework.procs.PushOrPullProcessor;

public class RelationalOperationProcessor extends PushOrPullProcessor {

	private static final Logger log = LoggerFactory.getLogger(RelationalOperationProcessor.class);
	private CompiledScript script;
	private ScriptEngine engine;
	private RelationalContext relationalContext;
	private String resultingColumn;
	private Object resultingColumnForcedDataType;
	private String engineName = "javascript";
	
	private BigDecimal emptyBigDecimal = new BigDecimal(0);
	private BigInteger emptyBigInt = new BigInteger("0");
	private String emptyString = "";
	
	@Override
	protected int getNumParams() {
		return 1;
	}
	
	@Override
	public String init(String pathStr, ProcessorSetup nextInChain, VisitorInfo visitor, HashMap<String, String> options) {
		String newPath = super.init(pathStr, nextInChain, visitor, options);
		System.setProperty("org.jruby.embed.localvariable.behavior", "persistent");
		String script = params.getParams().get(0);
		String lang = options.get("engineName");
		if (StringUtils.isNotBlank(lang)) 
			engineName = lang;
				
		ScriptEngineManager manager = new ScriptEngineManager();
		//com.sun.script.jython.JythonScriptEngineFactory f;
		com.sun.script.jruby.JRubyScriptEngine jrse;

		//System.out.println(" engines:   "+getSupportedEnginesString());
		engine = manager.getEngineByName(engineName);
		if (engine == null) {
			throw new BadRequest("The engineName you specified '"+engineName+"' is not available.  Supported scripting engines are " + getSupportedEnginesString()+".  An engineName can be any of the 'aliases' listed for an engine");
		}
		
		if (StringUtils.isEmpty(script))
			throw new BadRequest("Relational Module requires a script to run");
		
		try {
			initializeScript(script);
		}
		catch (ScriptException se) {
			throw new BadRequest("Your script is not legal");
		}
		relationalContext = new RelationalContext();
		return newPath;
	}
	
	private String getSupportedEnginesString() {
		String results = "";
		ScriptEngineManager mgr = new ScriptEngineManager();
        List<ScriptEngineFactory> factories = mgr.getEngineFactories();

        for (ScriptEngineFactory factory : factories) {

            String engName = factory.getEngineName();
            String engVersion = factory.getEngineVersion();
            String langName = factory.getLanguageName();
            String langVersion = factory.getLanguageVersion();

            results += "\nScript Engine: "+engName+" ("+engVersion+"), Language: "+langName+", Language Version "+langVersion;

            List<String> engNames = factory.getNames();
            results+="Engine Aliases:";
            for(String name : engNames) {
                results+=name+", ";
            }
        }
        return results;
	}

	private void initializeScript(String scriptText) throws ScriptException {
		resultingColumn = StringUtils.substringBefore(scriptText, "=");
		if (StringUtils.contains(resultingColumn, "(")) {
			String dataTypeString = StringUtils.substringAfter(resultingColumn, "(");
			dataTypeString = StringUtils.substringBefore(dataTypeString, ")");
			resultingColumn = StringUtils.substringBefore(resultingColumn, "(");
			setResultingColumnForcedDataTypeFromShortString(dataTypeString);
		}
		String toExecute = scriptText;
		//toExecute = StringUtils.substringAfter(scriptText, "=");
		toExecute=StringUtils.replace(toExecute, "÷", "/");
		//ScriptEngineManager manager = new ScriptEngineManager();
		//engine = manager.getEngineByName(engineName);
		if(!(engine instanceof Compilable)) {
			throw new BadRequest("the engine you specified "+engineName+" is not compilable!!  This is a configuration error in databus!");
		}
		script = ((Compilable) engine).compile(toExecute);
	}

	private void setResultingColumnForcedDataTypeFromShortString(
			String dataTypeString) {
		
		if (StringUtils.equals("BigDecimal", dataTypeString))
			resultingColumnForcedDataType = emptyBigDecimal;
		else if (StringUtils.equals("BigInteger", dataTypeString))
			resultingColumnForcedDataType = emptyBigDecimal;
		else if (StringUtils.equals("String", dataTypeString))
			resultingColumnForcedDataType = emptyString;
		else
			throw new BadRequest("unsupported data type "+dataTypeString+" only BigDecimal, BigInteger and String are allowed");

	}

	@Override
	protected TSRelational modifyRow(TSRelational tv) {
		
		relationalContext.addPrevious(tv);
		Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
		String attrPrefix = "";
		if (StringUtils.containsIgnoreCase(engineName, "ruby"))
			attrPrefix = "@";
		bindings.put("relationalContext", relationalContext);
		if (StringUtils.containsIgnoreCase(engineName, "ruby")) {
			for (Entry<String, Object> entry:tv.entrySet())
				bindings.put(attrPrefix+entry.getKey().toLowerCase(), entry.getValue());
		}
		else {
			for (Entry<String, Object> entry:tv.entrySet())
				bindings.put(attrPrefix+entry.getKey(), entry.getValue());
		}
		if (!bindings.containsKey(resultingColumn))
			bindings.put(attrPrefix+resultingColumn, null);
//		for (Entry<String, Object> e:bindings.entrySet())
//			System.out.println("bindings is  "+e.getKey()+":"+e.getValue());
		Object result;
		try {
			if (StringUtils.containsIgnoreCase(engineName, "python") 
					//||StringUtils.containsIgnoreCase(engineName, "ruby")
					) {
				script.eval(bindings);
				result = engine.get(resultingColumn);
			}
			else {
				result = script.eval(bindings);
			}
		}
		catch (ScriptException e) {
			if (e.getCause() instanceof RaiseException)
				throw new BadRequest("Your script failed to evaluate for "+tv+" with exception "+((RaiseException)e.getCause()).getException());
			throw new BadRequest("Your script failed to evaluate for "+tv+" with exception "+ExceptionUtils.getFullStackTrace(e.getCause()));
		}
		if(resultingColumnForcedDataType == null && result instanceof Double) {
			result = new BigDecimal(result+"");
		}
		tv.put(resultingColumn,  result);
		relationalContext.incRowNum();
		return tv;
	}

}
