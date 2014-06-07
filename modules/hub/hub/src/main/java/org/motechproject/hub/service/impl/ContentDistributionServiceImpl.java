package org.motechproject.hub.service.impl;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;
import org.motechproject.hub.mds.HubDistributionError;
import org.motechproject.hub.mds.HubPublisherTransaction;
import org.motechproject.hub.mds.HubSubscriberTransaction;
import org.motechproject.hub.mds.HubSubscription;
import org.motechproject.hub.mds.HubTopic;
import org.motechproject.hub.mds.service.HubDistributionErrorMDSService;
import org.motechproject.hub.mds.service.HubDistributionStatusMDSService;
import org.motechproject.hub.mds.service.HubPublisherTransactionMDSService;
import org.motechproject.hub.mds.service.HubSubscriberTransactionMDSService;
import org.motechproject.hub.mds.service.HubSubscriptionMDSService;
import org.motechproject.hub.mds.service.HubTopicMDSService;
import org.motechproject.hub.model.DistributionStatusLookup;
import org.motechproject.hub.model.SubscriptionStatusLookup;
import org.motechproject.hub.service.ContentDistributionService;
import org.motechproject.hub.service.DistributionServiceDelegate;
import org.motechproject.hub.util.HubUtils;
import org.motechproject.hub.web.HubController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class ContentDistributionServiceImpl implements
		ContentDistributionService {

	private final static Logger LOGGER = Logger.getLogger(HubController.class);

	private HubTopicMDSService hubTopicService;

	private HubDistributionErrorMDSService distributionErrorMDSService;

	private HubPublisherTransactionMDSService hubPublisherTransactionMDSService;

	private HubSubscriptionMDSService hubSubscriptionMDSService;

	private HubSubscriberTransactionMDSService hubSubscriberTransactionMDSService;

	
	@Autowired
	public ContentDistributionServiceImpl(HubTopicMDSService hubTopicService,
			HubSubscriptionMDSService hubSubscriptionMDSService,
			HubDistributionErrorMDSService distributionErrorMDSService,
			HubPublisherTransactionMDSService hubPublisherTransactionMDSService,
			HubSubscriberTransactionMDSService hubSubscriberTransactionMDSService) {
		this.hubTopicService = hubTopicService;
		this.hubSubscriptionMDSService = hubSubscriptionMDSService;
		this.distributionErrorMDSService=distributionErrorMDSService;
		this.hubPublisherTransactionMDSService=hubPublisherTransactionMDSService;
		this.hubSubscriberTransactionMDSService=hubSubscriberTransactionMDSService;
	}

	@Autowired
	private DistributionServiceDelegate distributionServiceDelegate;

	@Value("${max.retry.count}")
	private String maxRetryCount;

	public void setMaxRetryCount(String count) {
		this.maxRetryCount = count;
	}

	public DistributionServiceDelegate getDistributionServiceDelegate() {
		return distributionServiceDelegate;
	}

	public void setDistributionServiceDelegate(
			DistributionServiceDelegate distributionServiceDelegate) {
		this.distributionServiceDelegate = distributionServiceDelegate;
	}

	public ContentDistributionServiceImpl() {

	}

	@Override
	public void distribute(String url) {
		List<HubTopic> hubTopics = hubTopicService.findByTopicUrl(url);
		long topicId = -1;
		if (hubTopics == null || hubTopics.isEmpty()) {
			LOGGER.error("No Hub topics for the url " + url);

		} else if (hubTopics.size() > 1) {
			LOGGER.error("Multiple hub topics for the url " + url);
		} else {
			topicId = (long) hubTopicService.getDetachedField(
					hubTopics.get(0), "id");
		}

		if (hubTopics == null) {
			HubTopic hubTopic = new HubTopic();
			hubTopic.setTopicUrl(url);
			hubTopic = hubTopicService.create(hubTopic);
			topicId = (long) hubTopicService.getDetachedField(hubTopic, "id");
		
		}

		HubPublisherTransaction publisherTransaction = new HubPublisherTransaction();
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		publisherTransaction.setHubTopicId(Integer.valueOf((int)topicId));
		// TODO set publisherTransaction Notification Time
		//publisherTransaction.setNotificationTime(HubUtils.getCurrentDateTime());
		hubPublisherTransactionMDSService.create(publisherTransaction);

		// Get the content
		ResponseEntity<String> response = distributionServiceDelegate
				.getContent(url);

		// Ignore any status code other than 2xx
		if (response != null && response.getStatusCode().value() / 100 == 2) {
			String content = response.getBody();
			MediaType contentType = response.getHeaders().getContentType();
			List<HubSubscription> subscriptionList = hubSubscriptionMDSService
					.findSubByTopicId(Integer.valueOf((int)topicId));
			LOGGER.debug("Content received from Publisher: " + content);
			for (HubSubscription subscription : subscriptionList) {
				long subscriptionId = (long) hubSubscriptionMDSService
						.getDetachedField(subscription, "id");

				int retryCount = 0;
				DistributionStatusLookup statusLookup = DistributionStatusLookup.FAILURE;
				int subscriptionStatusId = Integer.valueOf(subscription
						.getHubSubscriptionStatusId());
				if (subscriptionStatusId == SubscriptionStatusLookup.INTENT_VERIFIED
						.getId()) {
					// distribute the content
					String callbackUrl = subscription.getCallbackUrl();
					ResponseEntity<String> distributionResponse = null;
					do {
						distributionResponse = distributionServiceDelegate
								.distribute(callbackUrl, content, contentType,
										url);
						if (distributionResponse == null
								|| distributionResponse.getStatusCode().value() / 100 != 2) {
							HubDistributionError error = new HubDistributionError();
							error.setHubSubscriptionId(Integer.valueOf((int)subscriptionId));
							String errorDescription = "Unknown error";
							if (distributionResponse != null
									&& distributionResponse.getBody() != null) {
								errorDescription = distributionResponse
										.getBody();
							}
							error.setErrorDescription(errorDescription);
							distributionErrorMDSService.create(error);
							retryCount++;
						} else {
							statusLookup = DistributionStatusLookup.SUCCESS;
							break;
						}
					} while (retryCount <= Integer.parseInt(maxRetryCount));

					if (statusLookup.equals(DistributionStatusLookup.FAILURE)) {
						retryCount--;
					}

				}
				HubSubscriberTransaction subscriberTransaction = new HubSubscriberTransaction();
				subscriberTransaction.setHubSubscriptionId(Integer.valueOf((int)subscriptionId));

				subscriberTransaction.setHubDistributionStatusId(statusLookup
						.getId());
				subscriberTransaction.setRetryCount(retryCount);
				subscriberTransaction.setContentType(contentType.toString());
				subscriberTransaction.setContent(content);
				hubSubscriberTransactionMDSService
						.create(subscriberTransaction);
			}
		}
	}
}
