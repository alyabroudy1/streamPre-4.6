package com.lagradost.cloudstream3.providers.arabseedV4.service.http

import com.lagradost.api.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

class RequestQueue(
    private val executeRequest: suspend (String, Map<String, String>) -> RequestResult,
    private val solveCfAndRequest: suspend (String) -> RequestResult,
    private val onDomainRedirect: suspend (String, String) -> Unit
) {
    private val TAG = "RequestQueue"
    private val mutex = Mutex()
    
    data class QueuedRequest(
        val url: String,
        val deferred: CompletableDeferred<RequestResult>,
        val action: suspend () -> RequestResult
    )
    
    private val pendingRequests = mutableMapOf<String, MutableList<QueuedRequest>>()
    
    suspend fun enqueue(url: String, headers: Map<String, String> = emptyMap()): RequestResult {
        return enqueueAction(url) { executeRequest(url, headers) }
    }

    suspend fun enqueueAction(url: String, action: suspend () -> RequestResult): RequestResult {
        val domain = extractDomain(url)
        val deferred = CompletableDeferred<RequestResult>()
        val request = QueuedRequest(url, deferred, action)
        
        val isLeader = mutex.withLock {
            val queue = pendingRequests.getOrPut(domain) { mutableListOf() }
            queue.add(request)
            queue.size == 1
        }
        
        if (isLeader) {
            executeAsLeader(domain, request)
        }
        
        return deferred.await()
    }
    
    private suspend fun executeAsLeader(domain: String, leader: QueuedRequest) {
        val result = leader.action()
        
        if (result.success) {
            leader.deferred.complete(result)
            runFollowersParallel(domain)
        } else if (result.isCloudflareBlocked) {
            val solveUrl = result.finalUrl?.takeIf { it.isNotBlank() } ?: leader.url
            val requestDomain = extractDomain(leader.url)
            val finalDomain = extractDomain(solveUrl)
            
            if (requestDomain != finalDomain && finalDomain.isNotBlank()) {
                onDomainRedirect(requestDomain, finalDomain)
            }
            
            val cfResult = solveCfAndRequest(solveUrl)
            
            if (cfResult.success) {
                // Retry original action
                val retryResult = leader.action()
                leader.deferred.complete(retryResult)
                verifyAndRunFollowers(domain)
            } else {
                leader.deferred.complete(cfResult)
                failAllFollowers(domain, "CF solve failed")
            }
        } else {
            leader.deferred.complete(result)
            failAllFollowers(domain, result.error?.message ?: "Leader failed")
        }
    }
    
    private suspend fun verifyAndRunFollowers(domain: String) {
        val followers = mutex.withLock {
            val list = pendingRequests[domain]
            pendingRequests.remove(domain)
            list?.drop(1) ?: emptyList()
        }
        
        if (followers.isEmpty()) return
        
        // Verify with first follower
        val verifier = followers.first()
        val verifyResult = verifier.action()
        
        if (verifyResult.success) {
            verifier.deferred.complete(verifyResult)
            coroutineScope {
                followers.drop(1).forEach { req ->
                    launch { req.deferred.complete(req.action()) }
                }
            }
        } else {
            followers.forEach { 
                it.deferred.complete(RequestResult.failure("Cookie verification failed")) 
            }
        }
    }
    
    private suspend fun runFollowersParallel(domain: String) {
        val followers = mutex.withLock {
            val list = pendingRequests[domain]
            pendingRequests.remove(domain)
            list?.drop(1) ?: emptyList()
        }
        
        coroutineScope {
            followers.forEach { req ->
                launch { req.deferred.complete(req.action()) }
            }
        }
    }
    
    private suspend fun failAllFollowers(domain: String, reason: String) {
        mutex.withLock {
            val followers = pendingRequests[domain]?.drop(1) ?: emptyList()
            followers.forEach { it.deferred.complete(RequestResult.failure(reason)) }
            pendingRequests.remove(domain)
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) { "" }
    }
}
