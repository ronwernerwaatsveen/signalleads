package com.signalcrm.signalleads.api;

import java.util.List;

/**
 * Fasiten fra SignalCRM: org.nr. til bedrifter som har kjøpt (eksisterende kunder, vunne deals) og
 * som har takket nei (tapte deals). Brukes til likhetsscore ("lookalike") og til å utelate dem fra
 * prospektering. Bare org.nr. sendes - ingen kundedata.
 */
public record ReferenceSet(List<String> customerOrgNumbers, List<String> lostOrgNumbers) {}
