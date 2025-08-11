package com.tronproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import javax.servlet.http.HttpServletRequest;
import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.Duration;
import java.math.BigInteger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@SpringBootApplication
@RestController
@CrossOrigin(origins = "*")
public class TronEcosystemProxy {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Object> pegasusData = new ConcurrentHashMap<>();
    private final String USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    private final String PEGASUS_FILE = "pegasus.json";
    
    public static void main(String[] args) {
        System.out.println("🎯 Starting Tron Smart Filter Responder...");
        SpringApplication.run(TronEcosystemProxy.class, args);
    }

    // Load and refresh Pegasus data
    @PostConstruct
    public void loadPegasusData() {
        try {
            if (Files.exists(Paths.get(PEGASUS_FILE))) {
                String content = new String(Files.readAllBytes(Paths.get(PEGASUS_FILE)));
                pegasusData = objectMapper.readValue(content, Map.class);
                System.out.println("✅ Loaded " + pegasusData.size() + " entries from Pegasus");
            }
        } catch (IOException e) {
            System.out.println("⚠️ Pegasus file not found, starting fresh");
            pegasusData = new ConcurrentHashMap<>();
        }
    }

    // ============ TRONSCAN API ENDPOINTS ============

    // 1. Transaction Details
    @GetMapping("/api/transaction-info")
    public ResponseEntity<Object> getTransactionInfo(@RequestParam String hash) {
        
        // Check if we have this transaction in Pegasus
        if (pegasusData.containsKey(hash)) {
            Map<String, Object> txData = (Map<String, Object>) pegasusData.get(hash);
            
            ObjectNode response = objectMapper.createObjectNode();
            response.put("hash", hash);
            response.put("block", (Long) txData.getOrDefault("blockNumber", 45000000L));
            response.put("timestamp", (Long) txData.getOrDefault("timestamp", System.currentTimeMillis()));
            response.put("confirmed", true);
            response.put("confirmations", 100);
            
            // Contract result
            ArrayNode contractRet = objectMapper.createArrayNode();
            ObjectNode contractResult = objectMapper.createObjectNode();
            contractResult.put("contractRet", "SUCCESS");
            contractRet.add(contractResult);
            response.set("contractRet", contractRet);
            
            // Transfer info if exists
            if (txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                ArrayNode transfers = objectMapper.createArrayNode();
                ObjectNode transferObj = objectMapper.createObjectNode();
                transferObj.put("from", (String) transfer.get("from"));
                transferObj.put("to", (String) transfer.get("to"));
                transferObj.put("amount", (String) transfer.get("amount"));
                transferObj.put("contract_address", USDT_CONTRACT);
                transfers.add(transferObj);
                response.set("transfers", transfers);
            }
            
            System.out.println("📤 Responded to transaction-info: " + hash);
            return ResponseEntity.ok(response);
        }
        
        // Not in our data - return empty/not found
        return ResponseEntity.notFound().build();
    }

    // 2. Account Wallet Info
    @GetMapping("/api/account/wallet")
    public ResponseEntity<Object> getAccountWallet(@RequestParam String address) {
        
        // Calculate USDT balance from our transfers
        BigInteger totalBalance = BigInteger.ZERO;
        boolean hasTransfers = false;
        
        for (Map.Entry<String, Object> entry : pegasusData.entrySet()) {
            Map<String, Object> txData = (Map<String, Object>) entry.getValue();
            if (txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                String toAddress = (String) transfer.get("to");
                if (address.equals(toAddress)) {
                    String amount = (String) transfer.get("amount");
                    totalBalance = totalBalance.add(new BigInteger(amount));
                    hasTransfers = true;
                }
            }
        }
        
        if (hasTransfers) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("address", address);
            
            ArrayNode tokens = objectMapper.createArrayNode();
            ObjectNode usdtToken = objectMapper.createObjectNode();
            usdtToken.put("token_id", USDT_CONTRACT);
            usdtToken.put("token_name", "TetherToken");
            usdtToken.put("token_abbr", "USDT");
            usdtToken.put("token_decimal", 6);
            usdtToken.put("balance", totalBalance.toString());
            usdtToken.put("token_type", "trc20");
            tokens.add(usdtToken);
            
            response.set("tokens", tokens);
            
            System.out.println("📤 Responded to account/wallet: " + address + " (Balance: " + totalBalance + ")");
            return ResponseEntity.ok(response);
        }
        
        // No balance in our system
        return ResponseEntity.notFound().build();
    }

    // 3. Contract Events
    @PostMapping("/api/contracts/smart-contract-triggers-batch")
    public ResponseEntity<Object> getContractEvents(@RequestBody Map<String, Object> request) {
        
        List<String> hashList = (List<String>) request.get("hashList");
        String contractAddress = (String) request.get("contractAddress");
        
        // Only respond if it's for USDT contract
        if (!USDT_CONTRACT.equals(contractAddress)) {
            return ResponseEntity.notFound().build();
        }
        
        ArrayNode events = objectMapper.createArrayNode();
        
        for (String hash : hashList) {
            if (pegasusData.containsKey(hash)) {
                Map<String, Object> txData = (Map<String, Object>) pegasusData.get(hash);
                if (txData.containsKey("transferLog")) {
                    Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                    
                    ObjectNode event = objectMapper.createObjectNode();
                    event.put("transaction_id", hash);
                    event.put("contract_address", USDT_CONTRACT);
                    event.put("event_name", "Transfer");
                    event.put("block_number", (Long) txData.getOrDefault("blockNumber", 45000000L));
                    event.put("timestamp", (Long) txData.getOrDefault("timestamp", System.currentTimeMillis()));
                    
                    // Event result
                    Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("from", (String) transfer.get("from"));
                    result.put("to", (String) transfer.get("to"));
                    result.put("value", (String) transfer.get("amount"));
                    event.set("result", result);
                    
                    events.add(event);
                }
            }
        }
        
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", events);
        response.put("total", events.size());
        
        System.out.println("📤 Responded to contract events: " + events.size() + " events");
        return ResponseEntity.ok(response);
    }

    // 4. Token Transfer Analysis
    @GetMapping("/api/tokenTransfer/analysis")
    public ResponseEntity<Object> getTokenTransferAnalysis(@RequestParam String token, @RequestParam(defaultValue = "10") int days) {
        
        if (!USDT_CONTRACT.equals(token)) {
            return ResponseEntity.notFound().build();
        }
        
        // Calculate statistics from our data
        BigInteger totalVolume = BigInteger.ZERO;
        int transferCount = 0;
        long startTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L);
        
        for (Map.Entry<String, Object> entry : pegasusData.entrySet()) {
            Map<String, Object> txData = (Map<String, Object>) entry.getValue();
            Long timestamp = (Long) txData.get("timestamp");
            
            if (timestamp != null && timestamp > startTime && txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                String amount = (String) transfer.get("amount");
                totalVolume = totalVolume.add(new BigInteger(amount));
                transferCount++;
            }
        }
        
        if (transferCount > 0) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("token", token);
            response.put("total_volume", totalVolume.toString());
            response.put("transfer_count", transferCount);
            response.put("avg_amount", totalVolume.divide(BigInteger.valueOf(transferCount)).toString());
            response.put("days", days);
            
            System.out.println("📤 Responded to token analysis: " + transferCount + " transfers");
            return ResponseEntity.ok(response);
        }
        
        return ResponseEntity.notFound().build();
    }

    // ============ TRONGRID WALLET APIs ============

    // GetAccount (gRPC style)
    @PostMapping("/wallet/getaccount")
    public ResponseEntity<Object> walletGetAccount(@RequestBody Map<String, Object> request) {
        String address = (String) request.get("address");
        
        // Calculate TRC20 balance for this address
        BigInteger usdtBalance = BigInteger.ZERO;
        boolean hasBalance = false;
        
        for (Map.Entry<String, Object> entry : pegasusData.entrySet()) {
            Map<String, Object> txData = (Map<String, Object>) entry.getValue();
            if (txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                if (address.equals(transfer.get("to"))) {
                    String amount = (String) transfer.get("amount");
                    usdtBalance = usdtBalance.add(new BigInteger(amount));
                    hasBalance = true;
                }
            }
        }
        
        if (hasBalance) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("address", address);
            response.put("balance", "0x" + usdtBalance.toString(16));
            
            // TRC20 balances
            ObjectNode trc20 = objectMapper.createObjectNode();
            trc20.put(USDT_CONTRACT, "0x" + usdtBalance.toString(16));
            response.set("trc20", trc20);
            
            System.out.println("📤 Responded to wallet/getaccount: " + address);
            return ResponseEntity.ok(response);
        }
        
        return ResponseEntity.notFound().build();
    }

    // TriggerConstantContract (for balanceOf calls)
    @PostMapping("/wallet/triggerconstantcontract")
    public ResponseEntity<Object> triggerConstantContract(@RequestBody Map<String, Object> request) {
        String contractAddress = (String) request.get("contract_address");
        String ownerAddress = (String) request.get("owner_address");
        
        if (USDT_CONTRACT.equals(contractAddress)) {
            // This is likely a balanceOf call
            BigInteger balance = calculateUSDTBalance(ownerAddress);
            
            if (balance.compareTo(BigInteger.ZERO) > 0) {
                ObjectNode response = objectMapper.createObjectNode();
                
                ArrayNode constantResult = objectMapper.createArrayNode();
                constantResult.add(balance.toString(16));
                response.set("constant_result", constantResult);
                
                System.out.println("📤 Responded to balanceOf: " + ownerAddress + " = " + balance);
                return ResponseEntity.ok(response);
            }
        }
        
        return ResponseEntity.notFound().build();
    }

    // Get Transaction By ID
    @PostMapping("/wallet/gettransactionbyid")
    public ResponseEntity<Object> getTransactionById(@RequestBody Map<String, Object> request) {
        String txid = (String) request.get("value");
        
        if (pegasusData.containsKey(txid)) {
            Map<String, Object> txData = (Map<String, Object>) pegasusData.get(txid);
            
            ObjectNode response = objectMapper.createObjectNode();
            response.put("txID", txid);
            
            ArrayNode ret = objectMapper.createArrayNode();
            ObjectNode retObj = objectMapper.createObjectNode();
            retObj.put("contractRet", "SUCCESS");
            ret.add(retObj);
            response.set("ret", ret);
            
            System.out.println("📤 Responded to gettransactionbyid: " + txid);
            return ResponseEntity.ok(response);
        }
        
        return ResponseEntity.notFound().build();
    }

    // Helper method to calculate USDT balance
    private BigInteger calculateUSDTBalance(String address) {
        BigInteger balance = BigInteger.ZERO;
        
        for (Map.Entry<String, Object> entry : pegasusData.entrySet()) {
            Map<String, Object> txData = (Map<String, Object>) entry.getValue();
            if (txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                if (address.equals(transfer.get("to"))) {
                    String amount = (String) transfer.get("amount");
                    balance = balance.add(new BigInteger(amount));
                }
            }
        }
        
        return balance;
    }

    // ============ UNIVERSAL API OVERRIDE HANDLER ============
    
    // Catch ALL TronGrid API calls
    @RequestMapping(value = "/v1/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Object> handleTronGridAPI(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        System.out.println("🌐 TronGrid API: " + method + " " + path);
        
        // Parse request body if exists
        Map<String, Object> requestData = new HashMap<>();
        if (body != null && !body.isEmpty()) {
            try {
                requestData = objectMapper.readValue(body, Map.class);
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }
        
        // Route to appropriate handler
        if (path.contains("/wallet/getaccount")) {
            return walletGetAccount(requestData);
        } else if (path.contains("/wallet/triggerconstantcontract")) {
            return triggerConstantContract(requestData);
        } else if (path.contains("/wallet/gettransactionbyid")) {
            return getTransactionById(requestData);
        } else if (path.contains("/contracts") && path.contains("/events")) {
            return getContractEventsFromPath(path, requestData);
        } else if (path.contains("/accounts") && path.contains("/transactions")) {
            return getAccountTransactions(path, requestData);
        }
        
        // If we don't handle this API, return a generic "success" or pass-through
        return createPassThroughResponse(path, requestData);
    }
    
    // Catch ALL TronScan API calls
    @RequestMapping(value = "/api/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Object> handleTronScanAPI(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        
        String path = request.getRequestURI();
        String method = request.getMethod();
        Map<String, String> params = new HashMap<>();
        
        // Extract query parameters
        request.getParameterMap().forEach((key, values) -> {
            if (values.length > 0) {
                params.put(key, values[0]);
            }
        });
        
        System.out.println("🔍 TronScan API: " + method + " " + path + " " + params);
        
        // Route based on path
        if (path.contains("/transaction-info")) {
            String hash = params.get("hash");
            if (hash != null) {
                return getTransactionInfo(hash);
            }
        } else if (path.contains("/account/wallet")) {
            String address = params.get("address");
            if (address != null) {
                return getAccountWallet(address);
            }
        } else if (path.contains("/contracts/smart-contract-triggers-batch")) {
            Map<String, Object> requestData = new HashMap<>();
            if (body != null) {
                try {
                    requestData = objectMapper.readValue(body, Map.class);
                } catch (Exception e) {
                    // Handle parsing error
                }
            }
            return getContractEvents(requestData);
        } else if (path.contains("/tokenTransfer/analysis")) {
            String token = params.get("token");
            int days = Integer.parseInt(params.getOrDefault("days", "10"));
            if (token != null) {
                return getTokenTransferAnalysis(token, days);
            }
        } else if (path.contains("/account") && path.contains("/transactions")) {
            return getAccountTransactionsFromPath(path, params);
        } else if (path.contains("/token_trc20/transfers")) {
            return getTRC20Transfers(params);
        }
        
        // Return appropriate "no data" response for TronScan
        return createTronScanEmptyResponse();
    }
    
    // Helper methods for new endpoints
    private ResponseEntity<Object> getContractEventsFromPath(String path, Map<String, Object> requestData) {
        // Extract contract address from path
        String contractAddress = extractContractAddressFromPath(path);
        
        if (USDT_CONTRACT.equals(contractAddress)) {
            // Return our fake events
            return getContractEvents(requestData);
        }
        
        return ResponseEntity.ok(createEmptyEventsResponse());
    }
    
    private ResponseEntity<Object> getAccountTransactions(String path, Map<String, Object> requestData) {
        String address = extractAddressFromPath(path);
        
        if (address != null) {
            return getTransactionsForAddress(address);
        }
        
        return ResponseEntity.ok(createEmptyTransactionsResponse());
    }
    
    private ResponseEntity<Object> getAccountTransactionsFromPath(String path, Map<String, String> params) {
        String address = extractAddressFromPath(path);
        
        if (address != null) {
            return getTransactionsForAddress(address);
        }
        
        return ResponseEntity.ok(createEmptyTransactionsResponse());
    }
    
    private ResponseEntity<Object> getTRC20Transfers(Map<String, String> params) {
        String contractAddress = params.get("contract");
        String relatedAddress = params.get("relatedAddress");
        
        if (USDT_CONTRACT.equals(contractAddress) || relatedAddress != null) {
            return getTRC20TransfersForParams(params);
        }
        
        return ResponseEntity.ok(createEmptyTransfersResponse());
    }
    
    private ResponseEntity<Object> getTransactionsForAddress(String address) {
        ArrayNode transactions = objectMapper.createArrayNode();
        
        for (Map.Entry<String, Object> entry : pegasusData.entrySet()) {
            Map<String, Object> txData = (Map<String, Object>) entry.getValue();
            if (txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                if (address.equals(transfer.get("to")) || address.equals(transfer.get("from"))) {
                    ObjectNode tx = objectMapper.createObjectNode();
                    tx.put("hash", entry.getKey());
                    tx.put("block", (Long) txData.getOrDefault("blockNumber", 45000000L));
                    tx.put("timestamp", (Long) txData.getOrDefault("timestamp", System.currentTimeMillis()));
                    tx.put("from", (String) transfer.get("from"));
                    tx.put("to", (String) transfer.get("to"));
                    tx.put("value", (String) transfer.get("amount"));
                    tx.put("token", "USDT");
                    tx.put("contractAddress", USDT_CONTRACT);
                    transactions.add(tx);
                }
            }
        }
        
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", transactions);
        response.put("total", transactions.size());
        response.put("rangeTotal", transactions.size());
        
        return ResponseEntity.ok(response);
    }
    
    private ResponseEntity<Object> getTRC20TransfersForParams(Map<String, String> params) {
        String address = params.get("relatedAddress");
        ArrayNode transfers = objectMapper.createArrayNode();
        
        for (Map.Entry<String, Object> entry : pegasusData.entrySet()) {
            Map<String, Object> txData = (Map<String, Object>) entry.getValue();
            if (txData.containsKey("transferLog")) {
                Map<String, Object> transferLog = (Map<String, Object>) txData.get("transferLog");
                Map<String, Object> transfer = (Map<String, Object>) transferLog.get("transfer");
                
                if (address == null || address.equals(transfer.get("to")) || address.equals(transfer.get("from"))) {
                    ObjectNode transferObj = objectMapper.createObjectNode();
                    transferObj.put("transaction_id", entry.getKey());
                    transferObj.put("from", (String) transfer.get("from"));
                    transferObj.put("to", (String) transfer.get("to"));
                    transferObj.put("amount", (String) transfer.get("amount"));
                    transferObj.put("contract_address", USDT_CONTRACT);
                    transferObj.put("block", (Long) txData.getOrDefault("blockNumber", 45000000L));
                    transferObj.put("timestamp", (Long) txData.getOrDefault("timestamp", System.currentTimeMillis()));
                    transfers.add(transferObj);
                }
            }
        }
        
        ObjectNode response = objectMapper.createObjectNode();
        response.set("token_transfers", transfers);
        response.put("total", transfers.size());
        
        return ResponseEntity.ok(response);
    }
    
    // Utility methods
    private String extractContractAddressFromPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("contracts".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }
    
    private String extractAddressFromPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (("account".equals(parts[i]) || "accounts".equals(parts[i])) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }
    
    private ResponseEntity<Object> createPassThroughResponse(String path, Map<String, Object> requestData) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("result", true);
        response.put("message", "API handled by proxy");
        return ResponseEntity.ok(response);
    }
    
    private ResponseEntity<Object> createTronScanEmptyResponse() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", objectMapper.createArrayNode());
        response.put("total", 0);
        return ResponseEntity.ok(response);
    }
    
    private ObjectNode createEmptyEventsResponse() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", objectMapper.createArrayNode());
        response.put("total", 0);
        return response;
    }
    
    private ObjectNode createEmptyTransactionsResponse() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", objectMapper.createArrayNode());
        response.put("total", 0);
        response.put("rangeTotal", 0);
        return response;
    }
    
    private ObjectNode createEmptyTransfersResponse() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("token_transfers", objectMapper.createArrayNode());
        response.put("total", 0);
        return response;
    }

    // Health check
    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "healthy");
        response.put("pegasus_entries", pegasusData.size());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    // Refresh Pegasus data endpoint
    @PostMapping("/refresh")
    public ResponseEntity<Object> refresh() {
        loadPegasusData();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "refreshed");
        response.put("entries", pegasusData.size());
        return ResponseEntity.ok(response);
    }
}
