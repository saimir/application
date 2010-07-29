package org.collectionspace.chain.csp.webui.authorities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.collectionspace.chain.csp.schema.Instance;
import org.collectionspace.chain.csp.schema.Option;
import org.collectionspace.chain.csp.schema.Record;
import org.collectionspace.chain.csp.schema.Spec;
import org.collectionspace.chain.csp.webui.main.Request;
import org.collectionspace.chain.csp.webui.main.WebMethod;
import org.collectionspace.chain.csp.webui.main.WebUI;
import org.collectionspace.csp.api.persistence.ExistException;
import org.collectionspace.csp.api.persistence.Storage;
import org.collectionspace.csp.api.persistence.UnderlyingStorageException;
import org.collectionspace.csp.api.persistence.UnimplementedException;
import org.collectionspace.csp.api.ui.UIException;
import org.collectionspace.csp.api.ui.UIRequest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * checks content of Vocabulary against a list and adds anything missing
 * eventually the service layer will have a function to do this so we wont have to do all the checking
 * 
 * @author caret
 *
 */
public class AuthoritiesVocabulariesInitialize implements WebMethod  {
	private static final Logger log=LoggerFactory.getLogger(AuthoritiesVocabulariesInitialize.class);
	private boolean append;
	private Instance n;
	private Record r;
	

	public AuthoritiesVocabulariesInitialize(Instance n, Boolean append) {
		this.append = append;
		this.n = n;
		this.r = n.getRecord();
	}
	public AuthoritiesVocabulariesInitialize(Record r, Boolean append) {
		this.append = append;
		this.r = r;
		this.n = null;
	}
	
	private JSONObject getDisplayNameList(Storage storage,String auth_type,String inst_type,String csid) throws ExistException, UnimplementedException, UnderlyingStorageException, JSONException {
		//should be using cached data (hopefully) from previous getPathsJson call
		JSONObject out=storage.retrieveJSON(auth_type+"/"+inst_type+"/"+csid+"/view");
		return out;
	}
		
	
	
	private JSONObject list_vocab(JSONObject displayNames,Instance n,Storage storage,UIRequest ui,String param, Integer pageSize, Integer pageNum) throws ExistException, UnimplementedException, UnderlyingStorageException, JSONException {
		JSONObject restriction=new JSONObject();
		if(param!=null){
			restriction.put(n.getRecord().getDisplayNameField().getID(),param);
		}
		if(pageNum!=null){
			restriction.put("pageNum",pageNum);
		}
		if(pageSize!=null){
			restriction.put("pageSize",pageSize);
		}
		JSONObject data = storage.getPathsJSON(r.getID()+"/"+n.getTitleRef(),restriction);
		String[] results = (String[]) data.get("listItems");
		/* Get a view of each */
		for(String result : results) {
			//change csid into displayName
			JSONObject datanames = getDisplayNameList(storage,r.getID(),n.getTitleRef(),result);
			
			displayNames.put(datanames.getString("displayName"),result);
		}
		JSONObject alldata = new JSONObject();
		alldata.put("displayName", displayNames);
		alldata.put("pagination",  data.getJSONObject("pagination"));
		return alldata;
	}
	
	private void initializeVocab(Storage storage,UIRequest request,String path) throws UIException {

		if(n==null) {
			// For now simply loop thr all the instances one after the other.
			for(Instance n : r.getAllInstances()) {
				resetvocabdata(storage, request, n);
			}
		} else {
			resetvocabdata(storage, request, this.n);
		}
	}
	
	
	private void resetvocabdata(Storage storage,UIRequest request, Instance instance) throws UIException {
		//get list from Spec
		Option[] allOpts = instance.getAllOptions();
		//step away if we have nothing
		if(allOpts != null && allOpts.length > 0){

			//get list from Service layer
			JSONObject results = new JSONObject();
			try {
				Integer pageNum = 0;
				Integer pageSize = 100;
				JSONObject fulldata= list_vocab(results,instance,storage,request,null, pageSize,pageNum);

				while(!fulldata.isNull("pagination")){
					Integer total = fulldata.getJSONObject("pagination").getInt("totalItems");
					pageSize = fulldata.getJSONObject("pagination").getInt("pageSize");
					Integer itemsInPage = fulldata.getJSONObject("pagination").getInt("itemsInPage");
					pageNum = fulldata.getJSONObject("pagination").getInt("pageNum");
					results=fulldata.getJSONObject("displayName");
					
					pageNum++;
					//are there more results
					if(total > (pageSize * (pageNum))){
						fulldata= list_vocab(results, instance, storage, request, null, pageSize, pageNum);
					}
					else{
						break;
					}
				}

				//compare
				results= fulldata.getJSONObject("displayName");

				//only add if term is not already present
				for(Option opt : allOpts){
					String name = opt.getName();
					String shortIdentifier = opt.getID();
					if(!results.has(name)){
						//create it if term is not already present
						JSONObject data=new JSONObject("{'displayName':'"+name+"'}");
						if(opt.getID() == null){
							//XXX here until the service layer does this
							shortIdentifier = name.replaceAll("\\W", "").toLowerCase();
						}
						data.put("shortIdentifier", shortIdentifier);
						storage.autocreateJSON(r.getID()+"/"+instance.getTitleRef(),data);
					}
					else{
						//remove from results so can delete everything else if necessary in next stage
						//tho has issues with duplicates
						results.remove(name);
					}
				}
				if(!this.append){
					//delete everything that is not in options
					Iterator<String> rit=results.keys();
					while(rit.hasNext()) {
						String key=rit.next();
						String csid = results.getString(key);
						storage.deleteJSON(r.getID()+"/"+instance.getTitleRef()+"/"+csid);
					}
				}

			} catch (JSONException e) {
				throw new UIException("Cannot generate JSON",e);
			} catch (ExistException e) {
				throw new UIException("Exist exception",e);
			} catch (UnimplementedException e) {
				throw new UIException("Unimplemented exception",e);
			} catch (UnderlyingStorageException e) {
				throw new UIException("Unnderlying storage exception",e);
			}
		}
	}
	
	public void configure(WebUI ui, Spec spec) {}

	public void run(Object in, String[] tail) throws UIException {
		Request q=(Request)in;
		initializeVocab(q.getStorage(),q.getUIRequest(),StringUtils.join(tail,"/"));
	}
}