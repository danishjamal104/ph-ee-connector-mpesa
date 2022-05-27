package org.mifos.connector.mpesa.flowcomponents.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelCollectionRequestDTO;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.mifos.connector.mpesa.utility.ZeebeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.util.Map;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.*;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.*;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;

@Component
public class MpesaWorker {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private SafaricomUtils safaricomUtils;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @PostConstruct
    public void setupWorkers() {

        zeebeClient.newWorker()
                .jobType("init-transfer")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                    Map<String, Object> variables = job.getVariablesAsMap();
                    TransactionChannelCollectionRequestDTO channelRequest = objectMapper.readValue(
                            (String) variables.get("mpesaChannelRequest"), TransactionChannelCollectionRequestDTO .class);
                    String transactionId = (String) variables.get(TRANSACTION_ID);

                    BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO = safaricomUtils.channelRequestConvertor(
                            channelRequest);

                    logger.info(buyGoodsPaymentRequestDTO.toString());
                    Exchange exchange = new DefaultExchange(camelContext);
                    exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO);
                    exchange.setProperty(CORRELATION_ID, transactionId);

                    variables.put(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO);

                    producerTemplate.send("direct:buy-goods-base", exchange);
                    variables.put(MPESA_API_RESPONSE, exchange.getProperty(MPESA_API_RESPONSE));
                    boolean isTransactionFailed = exchange.getProperty(TRANSACTION_FAILED, boolean.class);
                    if(isTransactionFailed) {
                        variables.put(TRANSACTION_FAILED, true);
                        variables.put(TRANSFER_CREATE_FAILED, true);
                        variables.put(TRANSFER_RESPONSE_CREATE, ZeebeUtils.getTransferResponseCreateJson());
                        String errorBody = exchange.getProperty(ERROR_INFORMATION, String.class);
                        variables.put(ERROR_INFORMATION, errorBody);
                        variables.put(ERROR_CODE, exchange.getProperty(ERROR_CODE, String.class));
                        variables.put(ERROR_DESCRIPTION, exchange.getProperty(ERROR_DESCRIPTION, String.class));
                    } else {
                        String serverTransactionId = exchange.getProperty(SERVER_TRANSACTION_ID, String.class);
                        variables.put(TRANSACTION_FAILED, false);
                        variables.put(TRANSFER_CREATE_FAILED, false);
                        variables.put(SERVER_TRANSACTION_ID, serverTransactionId);
                    }

                    client.newCompleteCommand(job.getKey())
                            .variables(variables)
                            .send()
                            .join();
                })
                .name("init-transfer")
                .maxJobsActive(workerMaxJobs)
                .open();

    }

}
