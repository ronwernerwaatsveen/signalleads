package com.signalcrm.signalleads.scoring;

import com.signalcrm.signalleads.ai.AnalysisOutcome;
import com.signalcrm.signalleads.brreg.RegnskapYear;

import java.util.List;

/**
 * Alt scoringen trenger om én bedrift, samlet før beregning.
 *
 * @param inactiveReason  satt hvis bedriften er konkurs/under avvikling (diskvalifiserer)
 * @param websiteTextChars hvor mye nettsidetekst som ble funnet (null = ingen nettside kjent/hentet)
 * @param ai              validert KI-resultat, eller null hvis KI ikke ble kjørt
 * @param aiNote          hvorfor KI ikke ble kjørt (budsjett, ikke konfigurert, feil)
 */
public record CompanyFacts(
        String naceCode,
        String naceDescription,
        Integer employees,
        String inactiveReason,
        List<RegnskapYear> regnskap,
        Integer websiteTextChars,
        AnalysisOutcome ai,
        String aiNote,
        boolean aiGloballyAvailable
) {}
