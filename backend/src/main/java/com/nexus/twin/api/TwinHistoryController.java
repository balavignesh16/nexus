package com.nexus.twin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.twin.api.dto.TwinHistoryResponse;
import com.nexus.twin.domain.TwinStateTransition;
import com.nexus.twin.domain.TwinStateTransitionRepository;
import com.nexus.twin.domain.TwinTransitionType;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/twin")
public class TwinHistoryController {
    private static final int MAX_CURSOR_LENGTH=1024;
    private final TwinStateTransitionRepository repository;
    private final ObjectMapper mapper;
    public TwinHistoryController(TwinStateTransitionRepository repository,ObjectMapper mapper){this.repository=repository;this.mapper=mapper;}

    @GetMapping("/history")
    public TwinHistoryResponse history(@PathVariable UUID deviceId,
            @RequestParam(required=false) UUID bindingId,@RequestParam(required=false) String legacyKey,
            @RequestParam(required=false) TwinTransitionType transitionType,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor) {
        if(bindingId!=null&&legacyKey!=null)throw new IllegalArgumentException("bindingId and legacyKey are mutually exclusive");
        if(limit<1||limit>200)throw new IllegalArgumentException("limit must be between 1 and 200");
        if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("from must not be after to");
        String canonicalLegacy=legacyKey==null?null:canonicalLegacy(legacyKey);
        String fingerprint=fingerprint(deviceId,bindingId,canonicalLegacy,transitionType,from,to);
        Cursor decoded=decode(cursor,fingerprint);
        List<TwinStateTransition> rows=repository.search(deviceId,bindingId,canonicalLegacy,transitionType,from,to,
                decoded==null?null:decoded.time(),decoded==null?null:decoded.id(),PageRequest.of(0,limit+1));
        boolean more=rows.size()>limit;
        List<TwinStateTransition> page=more?rows.subList(0,limit):rows;
        String next=more?encode(page.getLast(),fingerprint):null;
        return new TwinHistoryResponse(page.stream().map(TwinHistoryResponse.Item::from).toList(),next);
    }

    private record Cursor(int v,Instant time,UUID id,String filter){}
    private Cursor decode(String raw,String fingerprint){
        if(raw==null)return null;
        if(raw.isBlank()||raw.length()>MAX_CURSOR_LENGTH)throw new IllegalArgumentException("Malformed history cursor");
        try{
            Cursor value=mapper.readValue(Base64.getUrlDecoder().decode(raw),Cursor.class);
            if(value.v()!=1||value.time()==null||value.id()==null||!fingerprint.equals(value.filter()))
                throw new IllegalArgumentException("History cursor does not match filters");
            return value;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Malformed history cursor");}
    }
    private String encode(TwinStateTransition row,String fingerprint){
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
                new Cursor(1,row.getTransitionedAt(),row.getTransitionId(),fingerprint)));}
        catch(Exception e){throw new IllegalStateException("Could not encode history cursor",e);}
    }
    private static String fingerprint(UUID deviceId,UUID bindingId,String legacy,TwinTransitionType type,Instant from,Instant to){
        String input=String.join("|",deviceId.toString(),Objects.toString(bindingId,""),Objects.toString(legacy,""),
                Objects.toString(type,""),Objects.toString(from,""),Objects.toString(to,""));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String canonicalLegacy(String key){String v=key.toUpperCase(Locale.ROOT);return v.endsWith("_SENSOR")?v.substring(0,v.length()-7):v;}
}
