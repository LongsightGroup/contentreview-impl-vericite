package org.sakaiproject.contentreview.impl;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.impl.client.ApiClient;
import org.sakaiproject.contentreview.impl.client.ApiException;
import org.sakaiproject.contentreview.impl.client.api.DefaultApi;
import org.sakaiproject.contentreview.impl.client.model.AssignmentData;
import org.sakaiproject.contentreview.impl.client.model.ExternalContentData;
import org.sakaiproject.contentreview.impl.client.model.ExternalContentUploadInfo;
import org.sakaiproject.contentreview.impl.client.model.ReportMetaData;
import org.sakaiproject.contentreview.impl.client.model.ReportScoreReponse;
import org.sakaiproject.contentreview.impl.client.model.ReportURLLinkReponse;
import org.sakaiproject.contentreview.model.ContentReviewItem;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.ServerOverloadException;
import org.sakaiproject.memory.api.Cache;
import org.sakaiproject.memory.api.MemoryService;
import org.sakaiproject.memory.api.SimpleConfiguration;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;


public class ContentReviewServiceImpl implements ContentReviewService {

	private static Log log = LogFactory.getLog(ContentReviewServiceImpl.class);

	private ServerConfigurationService serverConfigurationService;
	private UserDirectoryService userDirectoryService;
	private EntityManager entityManager;
	private SecurityService securityService;
	private SiteService siteService;
	
	private static final String PARAM_USER_ROLE_INSTRUCTOR = "Instructor";
	private static final String PARAM_USER_ROLE_LEARNER = "Learner";
	private static final String SERVICE_NAME = "VeriCite";
	private static final String VERICITE_API_VERSION = "v1";
	private static final int VERICITE_SERVICE_CALL_THROTTLE_MINS = 2;
	private static final String VERICITE_CACHE_PLACEHOLDER = "VERICITE_LAST_CHECKED";
	
	private String serviceUrl;
	private String consumer;
	private String consumerSecret;
	
	private MemoryService memoryService;
	//Caches token requests for instructors so that we don't have to send a request for every student
	Cache userUrlCache, contentScoreCache, assignmentTitleCache;
	private static final int CACHE_EXPIRE_URLS_MINS = 20;

	private static final int CONTENT_SCORE_CACHE_MINS = 5;
	
	public void init(){
		serviceUrl = serverConfigurationService.getString("vericite.serviceUrl", "");
		consumer = serverConfigurationService.getString("vericite.consumer", "");
		consumerSecret = serverConfigurationService.getString("vericite.consumerSecret", "");
		userUrlCache = memoryService.createCache("org.sakaiproject.contentreview.vericite.ContentReviewServiceVeriCite.userUrlCache", new SimpleConfiguration(10000, CACHE_EXPIRE_URLS_MINS * 60, -1));
		contentScoreCache = memoryService.createCache("org.sakaiproject.contentreview.vericite.ContentReviewServiceVeriCite.contentScoreCache", new SimpleConfiguration(10000, CONTENT_SCORE_CACHE_MINS * 60, -1));
		assignmentTitleCache = memoryService.getCache("org.sakaiproject.contentreview.vericite.ContentReviewServiceVeriCite.assignmentTitleCache");
	}
	
	public boolean allowResubmission() {
		return true;
	}

	public void checkForReports() {
		
	}

	public void createAssignment(final String contextId, final String assignmentRef, final Map opts)
			throws SubmissionException, TransientSubmissionException {
		new Thread(){
			public void run() {
				boolean isA2 = isA2(null, assignmentRef);
				String assignmentId = getAssignmentId(assignmentRef, isA2);
				Map<String, ContentResource> attachmentsMap = new HashMap<String, ContentResource>();
				if(assignmentId != null){
					AssignmentData assignmentData = new AssignmentData();
					if(opts != null){
						if(opts.containsKey("title")){
							assignmentData.setAssignmentTitle(opts.get("title").toString());
						}else if(!isA2){
							//we can find the title from the assignment ref for A1
							String assignmentTitle = getAssignmentTitle(assignmentRef);
							if(assignmentTitle != null){
								assignmentData.setAssignmentTitle(assignmentTitle);
							}
						}
						if(opts.containsKey("instructions")){
							assignmentData.setAssignmentInstructions(opts.get("instructions").toString());
						}
						if(opts.containsKey("exclude_quoted")){
							assignmentData.setAssignmentExcludeQuotes("1".equals(opts.get("exclude_quoted").toString()));
						}
						if(opts.containsKey("dtdue")){
							SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
					        dform.applyPattern("yyyy-MM-dd HH:mm:ss");
					        try {
								Date dueDate = dform.parse(opts.get("dtdue").toString());
								if(dueDate != null){
									assignmentData.setAssignmentDueDate(dueDate.getTime());
								}
							} catch (ParseException e) {
								log.error(e.getMessage(), e);
							}
						}
						//Pass in 0 to delete a grade, otherwise, set the grade.
						assignmentData.setAssignmentGrade(0);
						if(opts.containsKey("points")){
							//points are stored as integers and multiplied by 100 (i.e. 5.5 = 550; 1 = 100, etc)
							try{
								Integer points = Integer.parseInt(opts.get("points").toString())/100;
								assignmentData.setAssignmentGrade(points);
							}catch(Exception e){
								log.error(e.getMessage(), e);
							}
						}
						if(opts.containsKey("attachments") && opts.get("attachments") instanceof List){
							SecurityAdvisor yesMan = new SecurityAdvisor(){
								public SecurityAdvice isAllowed(String arg0, String arg1, String arg2) {
									return SecurityAdvice.ALLOWED;
								}
							};
							securityService.pushAdvisor(yesMan);
							try{
								List<ExternalContentData> attachments = new ArrayList<ExternalContentData>();
								for(String refStr : (List<String>) opts.get("attachments")){
									try {
										Reference ref = entityManager.newReference(refStr);
										ContentResource res = (ContentResource) ref.getEntity();
										if(res != null){
											ExternalContentData attachment = new ExternalContentData();
											String fileName = res.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME);
											attachment.setFileName(FilenameUtils.getBaseName(fileName));
											attachment.setExternalContentID(getAssignmentAttachmentId(consumer, contextId, assignmentId, res.getId()));
											attachment.setUploadContentLength((int) res.getContentLength());
											attachment.setUploadContentType(FilenameUtils.getExtension(fileName));
											attachments.add(attachment);
											attachmentsMap.put(attachment.getExternalContentID(), res);
										}
									} catch (Exception e){
										log.error(e.getMessage(), e);
									}
								}
								if(attachments.size() > 0){
									assignmentData.setAssignmentAttachmentExternalContent(attachments);
								}
							}catch(Exception e){
								log.error(e.getMessage(), e);
							}finally{
								securityService.popAdvisor(yesMan);
							}
						}
					}
					DefaultApi vericiteApi = getVeriCiteAPI();
					try {
						List<ExternalContentUploadInfo> uploadInfo = vericiteApi.assignmentsContextIDAssignmentIDPost(contextId, assignmentId, consumer, consumerSecret, assignmentData);
						//see if there are any attachment presigned urls to upload to
						if(uploadInfo != null){
							//see if this attachment needs uploaded:
							for(ExternalContentUploadInfo info : uploadInfo){
								if(attachmentsMap.containsKey(info.getExternalContentId())){
									//upload this attachment
									ContentResource res = attachmentsMap.get(info.getExternalContentId());
									try {
										uploadExternalContent(info.getUrlPost(), res.getContent());
									} catch (ServerOverloadException e) {
										log.error(e.getMessage(), e);
									}
								}
							}
						}
					} catch (ApiException e) {
						log.error(e.getMessage(), e);
					}
				}
			}
		}.start();
	}

	public List<ContentReviewItem> getAllContentReviewItems(String arg0,
			String arg1) throws QueueException, SubmissionException,
			ReportException {
		// TODO Auto-generated method stub
		return null;
	}

	public Map getAssignment(String arg0, String arg1)
			throws SubmissionException, TransientSubmissionException {
		// TODO Auto-generated method stub
		return null;
	}

	public Date getDateQueued(String arg0) throws QueueException {
		// TODO Auto-generated method stub
		return null;
	}

	public Date getDateSubmitted(String arg0) throws QueueException,
			SubmissionException {
		// TODO Auto-generated method stub
		return null;
	}

	public String getIconUrlforScore(Long score) {
		String urlBase = "/sakai-contentreview-tool-vericite/images/";
		String suffix = ".png";

		if (score.compareTo(Long.valueOf(0)) < 0) {
			return urlBase + "greyflag" + suffix;
		}else if (score.equals(Long.valueOf(0))) {
			return urlBase + "blueflag" + suffix;
		} else if (score.compareTo(Long.valueOf(25)) < 0 ) {
			return urlBase + "greenflag" + suffix;
		} else if (score.compareTo(Long.valueOf(50)) < 0  ) {
			return urlBase + "yellowflag" + suffix;
		} else if (score.compareTo(Long.valueOf(75)) < 0 ) {
			return urlBase + "orangeflag" + suffix;
		} else {
			return urlBase + "redflag" + suffix;
		}
	}

	public String getLocalizedStatusMessage(String arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	public String getLocalizedStatusMessage(String arg0, String arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	public String getLocalizedStatusMessage(String arg0, Locale arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	public List<ContentReviewItem> getReportList(String siteId)
			throws QueueException, SubmissionException, ReportException {
		// TODO Auto-generated method stub
		return null;
	}

	public List<ContentReviewItem> getReportList(String siteId, String taskId)
			throws QueueException, SubmissionException, ReportException {
		return null;
	}

	public String getReviewReport(String contentId, String assignmentRef, String userId) throws QueueException,
			ReportException {
		return getAccessUrl(contentId, assignmentRef, userId, false);
	}

	public String getReviewReportInstructor(String contentId, String assignmentRef, String userId) throws QueueException,
			ReportException {
		/**
		 * contentId: /attachment/04bad844-493c-45a1-95b4-af70129d54d1/Assignments/b9872422-fb24-4f85-abf5-2fe0e069b251/plag.docx
		 */
		return getAccessUrl(contentId, assignmentRef, userId, true);
	}

	public String getReviewReportStudent(String contentId, String assignmentRef, String userId) throws QueueException,
			ReportException {
		return getAccessUrl(contentId, assignmentRef, userId, false);
	}
	
	private String getAccessUrl(String contentId, String assignmentRef, String userId, boolean instructor) throws QueueException, ReportException {
		//assignmentRef: /assignment/a/f7d8c921-7d5a-4116-8781-9b61a7c92c43/cbb993da-ea12-4e74-bab1-20d16185a655
		String context = getSiteIdFromConentId(contentId);
		if(context != null){
			String cacheKey = context + ":" + userId;
			String returnUrl = null;
			String assignmentId = getAssignmentId(assignmentRef, isA2(contentId, assignmentRef));
			//first check if cache already has the URL for this contentId and user
			if(userUrlCache.containsKey(cacheKey)){
				Map<String, Object[]> userUrlCacheObj = (Map<String, Object[]>) userUrlCache.get(cacheKey);
				if(userUrlCacheObj.containsKey(contentId)){
					//check if cache has expired:
					Object[] cacheItem = userUrlCacheObj.get(contentId);
					Calendar cal = Calendar.getInstance();
					cal.setTime(new Date());
					//subtract the exipre time (currently set to 20 while the plag token is set to 30, leaving 10 mins in worse case for instructor to use token)
					cal.add(Calendar.MINUTE, CACHE_EXPIRE_URLS_MINS * -1);
					if(((Date) cacheItem[1]).after(cal.getTime())){
						//token hasn't expired, use it
						returnUrl = (String) cacheItem[0];
					}else{
						//token is expired, remove it
						userUrlCacheObj.remove(contentId);
						userUrlCache.put(cacheKey, userUrlCacheObj);
					}
				}
			}
			
			if(StringUtils.isEmpty(returnUrl)){
				//instructors get all URLs at once, so only check VC every 2 minutes to avoid multiple calls in the same thread:
				boolean skip = false;
				if(instructor && userUrlCache.containsKey(cacheKey)){
					Map<String, Object[]> userUrlCacheObj = (Map<String, Object[]>) userUrlCache.get(cacheKey);
					if(userUrlCacheObj.containsKey(VERICITE_CACHE_PLACEHOLDER)){
						Object[] cacheItem = userUrlCacheObj.get(VERICITE_CACHE_PLACEHOLDER);
						Calendar cal = Calendar.getInstance();
						cal.setTime(new Date());
						//only check vericite every 2 mins to prevent subsequent calls from the same thread
						cal.add(Calendar.MINUTE, VERICITE_SERVICE_CALL_THROTTLE_MINS * -1);
						if(((Date) cacheItem[1]).after(cal.getTime())){
							//we just checked VC, skip asking again
							skip = true;
						}
					}
				}
				if(!skip){
					//we couldn't find the URL in the cache, so look it up (if instructor, look up all URLs so reduce the number of calls to the API)
					DefaultApi vericiteApi = getVeriCiteAPI();
					String tokenUserRole = PARAM_USER_ROLE_LEARNER;
					String externalContentIDFilter = null;
					if(instructor){
						tokenUserRole = PARAM_USER_ROLE_INSTRUCTOR;	
						//keep track of last call to make sure we don't call VC too much
						Object cacheObject = userUrlCache.get(cacheKey);
						if(cacheObject == null){
							cacheObject = new HashMap<String, Object[]>();
						}
						((Map<String, Object[]>) cacheObject).put(VERICITE_CACHE_PLACEHOLDER, new Object[]{VERICITE_CACHE_PLACEHOLDER, new Date()});
						userUrlCache.put(cacheKey, cacheObject);
					}else{
						//since students will only be able to see their own content, make sure to filter it:
						externalContentIDFilter = contentId;
					}
					List<ReportURLLinkReponse> urls = null;
					try {
						urls = vericiteApi.reportsUrlsContextIDGet(context, assignmentId, consumer, consumerSecret,  userId, tokenUserRole, null, externalContentIDFilter);
					} catch (ApiException e) {
						log.error(e.getMessage(), e);
					}
					if(urls != null){
						for(ReportURLLinkReponse url : urls){
							if(contentId.equals(url.getExternalContentID())){
								//this is the current url requested
								returnUrl = url.getUrl();
							}
							//store in cache for later
							//store in cache for later
							Object cacheObject = userUrlCache.get(cacheKey);
							if(cacheObject == null){
								cacheObject = new HashMap<String, Object[]>();
							}
							((Map<String, Object[]>) cacheObject).put(url.getExternalContentID(), new Object[]{url.getUrl(), new Date()});
							userUrlCache.put(cacheKey, cacheObject);
						}
					}
				}
			}
			if(StringUtils.isNotEmpty(returnUrl)){
				//we either found the url in the cache or from the API, return it
				return returnUrl;
			}
		}
		//shouldn't get here is all went well:
		throw new ReportException("Url was null or contentId wasn't correct: " + contentId);
	}

	public int getReviewScore(String contentId, String assignmentRef, String userId) throws QueueException,
			ReportException, Exception {
		/**
		 * contentId: /attachment/04bad844-493c-45a1-95b4-af70129d54d1/Assignments/b9872422-fb24-4f85-abf5-2fe0e069b251/plag.docx
		 * assignmentRef: /assignment/a/f7d8c921-7d5a-4116-8781-9b61a7c92c43/cbb993da-ea12-4e74-bab1-20d16185a655
		 */
		
		//first check if contentId already exists in cache:
		boolean isA2 = isA2(contentId, null);
		String context = getSiteIdFromConentId(contentId);
		Integer score = null;
		String assignment = getAssignmentId(assignmentRef, isA2);
		if(StringUtils.isNotEmpty(assignment)){
			if(contentScoreCache.containsKey(assignment)){
				Map<String, Map<String, Object[]>> contentScoreCacheObject = (Map<String, Map<String, Object[]>>) contentScoreCache.get(assignment);
				if(contentScoreCacheObject.containsKey(userId) 
						&& contentScoreCacheObject.get(userId).containsKey(contentId)){
					Object[] cacheItem = contentScoreCacheObject.get(userId).get(contentId);
					Calendar cal = Calendar.getInstance();
					cal.setTime(new Date());
					//subtract the exipre time
					cal.add(Calendar.MINUTE, CONTENT_SCORE_CACHE_MINS * -1);
					if(((Date) cacheItem[1]).after(cal.getTime())){
						//token hasn't expired, use it
						score = (Integer) cacheItem[0];
					}else{
						//token is expired, remove it
						contentScoreCacheObject.remove(userId);
						contentScoreCache.put(assignment, contentScoreCacheObject);
					}
				}
			}
		}		
		if(score == null){
			//wasn't in cache
			boolean skip = false;
			if(StringUtils.isNotEmpty(assignment)
					&& contentScoreCache.containsKey(assignment)){ 
				Map<String, Map<String, Object[]>> contentScoreCacheObject = (Map<String, Map<String, Object[]>>) contentScoreCache.get(assignment);
				if(contentScoreCacheObject.containsKey(VERICITE_CACHE_PLACEHOLDER)
						&& contentScoreCacheObject.get(VERICITE_CACHE_PLACEHOLDER).containsKey(VERICITE_CACHE_PLACEHOLDER)){
					Object[] cacheItem = contentScoreCacheObject.get(VERICITE_CACHE_PLACEHOLDER).get(VERICITE_CACHE_PLACEHOLDER);
					Calendar cal = Calendar.getInstance();
					cal.setTime(new Date());
					//only check vericite every 2 mins to prevent subsequent calls from the same thread
					cal.add(Calendar.MINUTE, VERICITE_SERVICE_CALL_THROTTLE_MINS * -1);
					if(((Date) cacheItem[1]).after(cal.getTime())){
						//we just checked VC, skip asking again
						skip = true;
					}
				}
			}
			//look up score in VC 
			if(context != null && !skip){
				DefaultApi vericiteApi = getVeriCiteAPI();
				String externalContentID = null;			
				if(assignmentRef == null){
					externalContentID = contentId;
				}			
				List<ReportScoreReponse> scores = vericiteApi.reportsScoresContextIDGet(context, consumer, consumerSecret, assignment, null, externalContentID);
				if(scores != null){
					for(ReportScoreReponse scoreResponse : scores){
						if(contentId.equals(scoreResponse.getExternalContentId())){
							score = scoreResponse.getScore();
						}
						//only cache the score if it is > 0
						if(scoreResponse.getScore() != null && scoreResponse.getScore().intValue() >= 0){
							Object userCacheMap = contentScoreCache.get(scoreResponse.getAssignment());
							if(userCacheMap == null){
								userCacheMap = new HashMap<String, Map<String, Object[]>>();
							}
							Map<String, Object[]> cacheMap = ((Map<String, Map<String, Object[]>>) userCacheMap).get(scoreResponse.getUser());
							if(cacheMap == null){
								cacheMap = new HashMap<String, Object[]>();
							}
							cacheMap.put(scoreResponse.getExternalContentId(), new Object[]{scoreResponse.getScore(), new Date()});
							((Map<String, Map<String, Object[]>>) userCacheMap).put(scoreResponse.getUser(), cacheMap);								
							contentScoreCache.put(scoreResponse.getAssignment(), userCacheMap);
						}
					}
				}
				//keep track of last call to make sure we don't call VC too much
				if(StringUtils.isNotEmpty(assignment)){
					Object userCacheMap = contentScoreCache.get(assignment);
					if(userCacheMap == null){
						userCacheMap = new HashMap<String, Map<String, Object[]>>();
					}
					Map<String, Object[]> cacheMap = ((Map<String, Map<String, Object[]>>) userCacheMap).get(VERICITE_CACHE_PLACEHOLDER);
					if(cacheMap == null){
						cacheMap = new HashMap<String, Object[]>();
					}
					cacheMap.put(VERICITE_CACHE_PLACEHOLDER, new Object[]{0, new Date()});
					((Map<String, Map<String, Object[]>>) userCacheMap).put(VERICITE_CACHE_PLACEHOLDER, cacheMap);								
					contentScoreCache.put(assignment, userCacheMap);
				}
				if(score == null){
					//nothing was found, throw exception for this contentId
					throw new QueueException("No report was found for contentId: " + contentId);
				}else{
					if(assignmentRef == null){
						//score wasn't null and there should have only been one score, so just return that value
						return score;
					}else{
						//grab the score from the map if it exists, if not, then there could have been an error:
						if(contentScoreCache.containsKey(assignment) && ((Map<String, Map<String, Object[]>>) contentScoreCache.get(assignment)).containsKey(userId)
								&& ((Map<String, Map<String, Object[]>>) contentScoreCache.get(assignment)).get(userId).containsKey(contentId)){
							return (Integer) ((Map<String, Map<String, Object[]>>) contentScoreCache.get(assignment)).get(userId).get(contentId)[0];
						}else{
							throw new QueueException("No report was found for contentId: " + contentId);		
						}
					}
				}
			}else{
				//content id is bad
				throw new ReportException("Couldn't find report score for contentId: " + contentId);
			}
		}else{
			return score;
		}
	}

	public Long getReviewStatus(String contentId) throws QueueException {
		//dont worry about implementing this, our status is always ready
		return null;
	}

	public String getServiceName() {
		return "VeriCite";
	}

	public boolean isAcceptableContent(ContentResource arg0) {
		return true;
	}

	public boolean isSiteAcceptable(Site arg0) {
		return true;
	}

	public void processQueue() {
		// TODO Auto-generated method stub
		
	}
	
	public void queueContent(String userId, String siteId, String assignmentReference, List<ContentResource> content) throws QueueException{
		List<FileSubmission> fileSubmissions = new ArrayList<FileSubmission>();
		if(content != null){
			for(ContentResource res : content){
				try {
					if(res != null){
						if(userId == null || "".equals(userId.trim())){
							userId = res.getProperties().getProperty(ResourceProperties.PROP_CREATOR);
						}
						String fileName = res.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME);
						fileSubmissions.add(new FileSubmission(res.getId(), fileName, res.getContent(), res.getContentLength()));
					}
				}catch(Exception e){
					throw new QueueException(e);
				}
			}
		}
		if(fileSubmissions.size() > 0){
			queue(userId, siteId, assignmentReference, fileSubmissions);
		}
	}

	private void queue(final String userId, String siteId, final String assignmentReference, final List<FileSubmission> fileSubmissions){
		/**
		 * Example call:
		 * userId: 124124124
		 * siteId: 452351421
		 * assignmentReference: /assignment/a/04bad844-493c-45a1-95b4-af70129d54d1/fa40eac1-5396-4a71-9951-d7d64b8a7710
		 * contentId: /attachment/04bad844-493c-45a1-95b4-af70129d54d1/Assignments/b9872422-fb24-4f85-abf5-2fe0e069b251/plag.docx
		 */
		
		if(fileSubmissions != null && fileSubmissions.size() > 0){
			final String contextParam = getSiteIdFromConentId(fileSubmissions.get(0).contentId);
			final String assignmentParam = getAssignmentId(assignmentReference, isA2(fileSubmissions.get(0).contentId, null));
			if(contextParam != null && assignmentParam != null){
				User u;
				try {
					u = userDirectoryService.getUser(userId);
					final String userFirstNameParam = u.getFirstName();
					final String userLastNameParam = u.getLastName();
					final String userEmailParam = u.getEmail();
					//it doesn't matter, all users are learners in the Sakai Integration
					final String userRoleParam = PARAM_USER_ROLE_LEARNER;

					new Thread(){
						public void run() {

							DefaultApi veriCiteApi = getVeriCiteAPI();
							ReportMetaData reportMetaData = new ReportMetaData();
							//get assignment title
							String assignmentTitle = getAssignmentTitle(assignmentReference);
							if(assignmentTitle != null){
								reportMetaData.setAssignmentTitle(assignmentTitle);
							}
							//get site title
							try{
								Site site = siteService.getSite(contextParam);
								if(site != null){
									reportMetaData.setContextTitle(site.getTitle());
								}
							}catch(Exception e){
								//no worries								
							}
							reportMetaData.setUserEmail(userEmailParam);
							reportMetaData.setUserFirstName(userFirstNameParam);
							reportMetaData.setUserLastName(userLastNameParam);
							reportMetaData.setUserRole(userRoleParam);
							List<ExternalContentData> externalContentDataList = new ArrayList<ExternalContentData>();
							if(fileSubmissions != null){
								for(FileSubmission f : fileSubmissions){						
									ExternalContentData externalContentData = new ExternalContentData();
									externalContentData.setExternalContentID(f.contentId);
									externalContentData.setFileName(FilenameUtils.getBaseName(f.fileName));
									externalContentData.setUploadContentType(FilenameUtils.getExtension(f.fileName));
									externalContentData.setUploadContentLength((int) f.contentLength);
									externalContentDataList.add(externalContentData);
								}
							}
							reportMetaData.setExternalContentData(externalContentDataList);

							List<ExternalContentUploadInfo> uploadInfo = null;
							try {
								uploadInfo = veriCiteApi.reportsSubmitRequestContextIDAssignmentIDUserIDPost(contextParam, assignmentParam, userId, consumer, consumerSecret, reportMetaData);
							} catch (ApiException e) {
								log.error(e.getMessage(), e);
							}
							//see if there are any attachment presigned urls to upload to
							if(uploadInfo != null){
								//see if this attachment needs uploaded:
								for(ExternalContentUploadInfo info : uploadInfo){
									if(fileSubmissions != null){
										for(FileSubmission f : fileSubmissions){
											if(f.contentId.equals(info.getExternalContentId())){
												uploadExternalContent(info.getUrlPost(), f.data);
												break;
											}
										}
									}
								}
							}
						}
					}.start();
				} catch (UserNotDefinedException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
	}

	public void removeFromQueue(String arg0) {
		// TODO Auto-generated method stub
		
	}

	public void resetUserDetailsLockedItems(String arg0) {
		// TODO Auto-generated method stub
		
	}
	

	public String getReviewError(String contentId){
		return null;
	}
	
	private String getAssignmentTitle(String assignmentRef){
		if(assignmentTitleCache.containsKey(assignmentRef)){
			return (String) assignmentTitleCache.get(assignmentRef);
		}else{
			String assignmentTitle = null;
			if (assignmentRef.startsWith("/assignment/")) {
				try {
					Reference ref = entityManager.newReference(assignmentRef);
					EntityProducer ep = ref.getEntityProducer();
					Entity ent = ep.getEntity(ref);
					if(ent != null){
						assignmentTitle = URLDecoder.decode(ent.getClass().getMethod("getTitle").invoke(ent).toString(),"UTF-8");
						assignmentTitleCache.put(assignmentRef, assignmentTitle);
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
				}
			}
			return assignmentTitle;
		}
	}
		
	private class FileSubmission{
		public String contentId;
		public byte[] data;
		public String fileName;
		public long contentLength = 0;
		
		public FileSubmission(String contentId, String fileName, byte[] data, long contentLength){
			this.contentId = contentId;
			this.fileName = fileName;
			this.data = data;
			this.contentLength = contentLength;
		}
	}

	private boolean isA2(String contentId, String assignmentRef){
		if(contentId != null && contentId.contains("/Assignment2/")){
			return true;
		}
		if(assignmentRef != null && assignmentRef.startsWith("/asnn2contentreview/")){
			return true;
		}
		return false;
	}
	
	private String getSiteIdFromConentId(String contentId){
		//contentId: /attachment/04bad844-493c-45a1-95b4-af70129d54d1/Assignments/b9872422-fb24-4f85-abf5-2fe0e069b251/plag.docx
		if(contentId != null){
			String[] split = contentId.split("/");
			if(split.length > 2){
				return split[2];
			}
		}
		return null;
	}
	
	private String getAssignmentId(String assignmentRef, boolean isA2){
		if(assignmentRef != null){
			String[] split = assignmentRef.split("/");
			if(isA2){
				if(split.length > 2){
					return split[2];
				}
			}else{
				if(split.length > 4){
					return split[4];
				}
			}
		}
		return null;
	}

	public boolean allowAllContent() {
		return true;
	}

	public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes() {
		return new HashMap<String, SortedSet<String>>();
	}

	public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions() {
		return new HashMap<String, SortedSet<String>>();
	}
	
	private DefaultApi getVeriCiteAPI(){
		ApiClient apiClient = new ApiClient();
		String apiUrl = serviceUrl;
		if(StringUtils.isEmpty(apiUrl) || !apiUrl.endsWith("/")){
			apiUrl += "/";
		}
		apiUrl += VERICITE_API_VERSION;
		apiClient.setBasePath(apiUrl);
		return new DefaultApi(apiClient);
	}
	
	private String getAssignmentAttachmentId(String consumer, String contextId, String assignmentId, String attachmentId){
		return "/" + consumer + "/" + contextId + "/" + assignmentId + "/" + attachmentId;
	}
	
	private void uploadExternalContent(String urlString, byte[] data){
		URL url = null;
		HttpURLConnection connection = null;
		DataOutputStream out = null;
		try {
			url = new URL(urlString);

			connection=(HttpURLConnection) url.openConnection();
			connection.setDoOutput(true);
			connection.setRequestMethod("PUT");
			out = new DataOutputStream(connection.getOutputStream());
			out.write(data);
			out.close();
			int responseCode = connection.getResponseCode();
			if(responseCode < 200 || responseCode >= 300){
				log.error("VeriCite upload content failed with code: " + responseCode);
			}
		} catch (MalformedURLException e) {
			log.error(e.getMessage(), e);
		} catch (IOException e) {
			log.error(e.getMessage(), e);
		}finally {
			if(out != null){
				try{
					out.close();
				}catch(Exception e){
					log.error(e.getMessage(), e);
				}
			}
		}
		
	}

	public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
		this.serverConfigurationService = serverConfigurationService;
	}

	public void setUserDirectoryService(UserDirectoryService userDirectoryService) {
		this.userDirectoryService = userDirectoryService;
	}

	public void setEntityManager(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	public void setSecurityService(SecurityService securityService) {
		this.securityService = securityService;
	}

	public void setSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

	public MemoryService getMemoryService() {
		return memoryService;
	}

	public void setMemoryService(MemoryService memoryService) {
		this.memoryService = memoryService;
	}
	
}
