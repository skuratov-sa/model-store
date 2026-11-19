package com.model_store.controller;

import com.model_store.model.dto.*;
import com.model_store.service.JwtService;
import com.model_store.service.impl.OrderTermsService;
import com.model_store.service.impl.AdminAgentService;
import com.model_store.repository.ParticipantRepository;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OrderTermsController {
    private final OrderTermsService terms;
    private final JwtService jwt;
    private final ParticipantRepository participants;
    private final AdminAgentService agents;

    @PutMapping("/order/{id}/delivery")
    public Mono<Long> delivery(@RequestHeader("Authorization") String auth, @PathVariable Long id,
                              @RequestBody OrderTermsRequest request) {
        Long actor = jwt.getIdByAccessToken(auth);
        return human(actor).then(terms.delivery(id, actor, actor, request));
    }

    @PostMapping("/order/{id}/payments/{stage}")
    public Mono<Long> submit(@RequestHeader("Authorization") String auth, @PathVariable Long id,
                            @PathVariable String stage, @RequestBody OrderPaymentRequest request) {
        if (!stage.equals("prepayment") && !stage.equals("full"))
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Неизвестный этап оплаты"));
        return terms.submit(id, jwt.getIdByAccessToken(auth), request, stage.equals("prepayment"));
    }

    @PostMapping("/order/{id}/payments/review/{action}")
    public Mono<Long> review(@RequestHeader("Authorization") String auth, @PathVariable Long id,
                            @PathVariable String action, @RequestBody OrderPaymentRequest request) {
        Long actor = jwt.getIdByAccessToken(auth);
        return human(actor).then(validAction(action)).then(terms.review(id, actor, actor, request, action.equals("approve")));
    }

    @GetMapping("/order/{id}/terms-history")
    public Flux<Map<String,Object>> history(@RequestHeader("Authorization") String auth, @PathVariable Long id) {
        return terms.history(id, jwt.getIdByAccessToken(auth));
    }

    @PostMapping("/order/{id}/dispute/payment-evidence")
    public Mono<Long> disputeEvidence(@RequestHeader("Authorization") String auth, @PathVariable Long id,
                                     @RequestBody OrderPaymentRequest request) {
        return terms.disputeEvidence(id,jwt.getIdByAccessToken(auth),request);
    }

    @PutMapping("/admin/actions/agents/{agentId}/orders/{id}/delivery")
    public Mono<Long> agentDelivery(@RequestHeader("Authorization") String auth, @PathVariable Long agentId,
                                   @PathVariable Long id, @RequestBody OrderTermsRequest request) {
        Long actor = jwt.getIdByAccessToken(auth);
        return admin(actor,agentId).then(agents.changeDelivery(actor,agentId,id,request));
    }

    @PostMapping("/admin/actions/agents/{agentId}/orders/{id}/payments/review/{action}")
    public Mono<Long> agentReview(@RequestHeader("Authorization") String auth, @PathVariable Long agentId,
                                 @PathVariable Long id, @PathVariable String action, @RequestBody OrderPaymentRequest request) {
        Long actor = jwt.getIdByAccessToken(auth);
        return admin(actor,agentId).then(validAction(action))
                .then(agents.reviewPayment(actor,agentId,id,request,action.equals("approve")));
    }

    @GetMapping("/admin/actions/agents/{agentId}/orders/{id}/terms-history")
    public Flux<Map<String,Object>> agentHistory(@RequestHeader("Authorization") String auth, @PathVariable Long agentId,
                                               @PathVariable Long id) {
        return admin(jwt.getIdByAccessToken(auth),agentId).thenMany(terms.history(id,agentId));
    }

    private Mono<Void> admin(Long actor, Long agentId) {
        return participants.findById(actor).filter(p -> p.getRole() == ParticipantRole.ADMIN
                && p.getStatus() == com.model_store.model.constant.ParticipantStatus.ACTIVE)
            .switchIfEmpty(Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED,"Требуется администратор")))
            .then(agents.requireAgent(agentId)).then();
    }

    @PostMapping("/admin/actions/orders/{id}/reconcile-terms")
    public Mono<Long> reconcile(@RequestHeader("Authorization") String auth, @PathVariable Long id,
                               @RequestBody OrderReconciliationRequest request) {
        Long actor = jwt.getIdByAccessToken(auth);
        return participants.findById(actor).filter(p -> p.getRole() == ParticipantRole.ADMIN
                && p.getStatus() == com.model_store.model.constant.ParticipantStatus.ACTIVE)
            .switchIfEmpty(Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED,"Требуется администратор")))
            .then(terms.reconcile(id,actor,request));
    }

    private Mono<Void> human(Long actor) {
        return participants.findByIdAndIsAgentTrue(actor).hasElement().flatMap(bot -> bot
            ? Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED,"Заказами бота управляет администратор")) : Mono.empty());
    }

    private Mono<Void> validAction(String action) {
        return action.equals("approve") || action.equals("return") ? Mono.empty()
            : Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST,"Неизвестное действие"));
    }
}
