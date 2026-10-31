package zw.co.unipay.payments.card

/**
 * The terminal's EMV application (AID) and certification-authority public key (CAPK) tables.
 *
 * One home for both, so every terminal library configures its kernel from the same source and
 * the two cannot drift apart. The tables are kept as the BER-TLV hex they were written in rather
 * than as a structured model: each library parses the TLV into whatever its own kernel takes
 * ([tlvOf] is the shared parser), and 650-odd lines of hand-written hex are not worth the risk of
 * re-expressing. A wrong character here declines every card of that scheme at the counter, with
 * nothing on screen to say why — see EmvConfigurationTest.
 *
 * ## Tag dictionary
 *
 * Several tags below are vendor-private (the DFxx and DFC1xx ones), and nothing else in this
 * codebase says what they mean. This dictionary was written from how the first adapter (CS20's
 * EmvAidCapkData.hexStr2Aid and hexStr2Capk) consumes each tag, so "feeds" names the field of
 * that kernel's AidV2 or CapkV2 bean the value is copied into. Where a meaning comes only from
 * that field's name, or from a standard the code does not cite, the line says so; where it could
 * not be established, the line says UNCERTAIN. Treat an UNCERTAIN line as a question, not an answer.
 * Values quoted are those in the tables below.
 *
 * AID entries (every one of the 20 carries all 29 of these tags, once each):
 *  - 9F06  Application Identifier as the terminal holds it. May be a partial: a 5-byte RID alone
 *          matches every product of that scheme. EMV standard tag. Feeds aid.
 *  - DF01  Application selection flag. 00 = partial match (the AID is a prefix; whatever PIX the
 *          card carries still selects), 01 = exact match. Established by the ZCS SmartPos
 *          documentation (doc/EMV_API_en.doc, the EmvApp listing: "selFlag //DF01 (=0 partial
 *          match, =1 exact match)"), which agrees with the AID 8c comment below. Only AID 14b
 *          uses 01; its historical comment called 01 "partial match" and was mistaken (now
 *          corrected). The Ciontek adapter copies the byte unchanged, so it implies no direction
 *          of its own. Feeds selFlag.
 *  - 9F09  Application version number held by the terminal, 2 bytes: 008C in the Visa/Discover
 *          style entries, 0002 in the Mastercard/AMEX style ones. EMV standard tag. Feeds version.
 *          (9F08, the card's version number, is in neither table and the adapter does not read it.)
 *          The ZCS vendor doc is inconsistent on this tag: its EmvApp field comment says 9F09
 *          while its own addApp TLV example uses 9F08. We follow 9F09, which the table uses.
 *  - DF11, DF12, DF13  Terminal Action Code Default, Online and Denial (the EMV TACs), 5 bytes
 *          each. Meaning from the field names and the EMV TAC convention. Feed TACDefault,
 *          TACOnline, TACDenial.
 *  - DF14  Default DDOL, the data-object list used when the card supplies none: 9F3704 (the
 *          Unpredictable Number). From the field name. Feeds dDOL.
 *  - DF15, DF16, DF17  Threshold value, maximum target percentage and target percentage for biased
 *          random selection in terminal risk management. From the field names only; UNCERTAIN
 *          that the vendor means exactly that. Feed threshold, maxTargetPer, targetPer.
 *  - 9F1B  Terminal floor limit, 4 bytes (00004000 = 16384 minor units). EMV standard tag. Feeds
 *          floorLimit.
 *  - 9F7B  The two vendors name it differently. Ciontek feeds it to termOfflineFloorLmt; the ZCS
 *          documentation names the same tag ecTTLVal, 6 bytes, and gives no expansion of that
 *          name. The name suggests an electronic-cash terminal limit; that is a reading of the
 *          name, not something the vendor states. The vendors' names disagree, so what it means
 *          is not established. Every entry holds 6 zero bytes, so nothing depends on it today.
 *  - DF19, DF20, DF21  Contactless offline floor limit, contactless transaction limit and CVM
 *          limit. From the field names only. Feed termClssOfflineFloorLmt, termClssLmt, cvmLmt.
 *  - 9F01  Acquirer Identifier. EMV standard tag. Feeds AcquierId (sic).
 *  - 9F4E  Merchant Name and Location, ASCII ("SHOP 2" in every entry). EMV standard tag. Feeds
 *          merchName.
 *  - 9F15  Merchant Category Code. EMV standard tag. Feeds merchCateCode.
 *  - 9F16  Merchant Identifier, 15 bytes (ASCII zeros in every entry). EMV standard tag. Feeds
 *          merchId.
 *  - 9F3C, 9F3D  Transaction Reference Currency Code and its exponent. EMV standard tags. Feed
 *          referCurrCode, referCurrExp.
 *  - DF8101  Reference currency conversion (0840). From the field name only. Feeds referCurrCon.
 *  - DF8102  Default TDOL, the transaction-certificate data-object list: 9F3704. Evidence: the
 *          Ciontek vendor demo source (posDemo UpdateDataController.java) labels the same
 *          "DF8102039F3704" as "Default TDOL", and the Ciontek field it feeds is tDOL. Open
 *          question: the ZCS documentation tags its own tDOL field 97, and nothing we have
 *          settles whether ZCS's tag 97 refers to the same object as Ciontek's DF8102. Feeds tDOL.
 *  - DFC108  Feeds clsStatusCheck: a contactless status check switch, from the field name only.
 *  - DFC109  Feeds zeroCheck: a zero-amount check switch, from the field name only.
 *  - DFC10A  Kernel type, the contactless kernel that handles the AID. The values are established
 *          by the per-entry comments below: 01 generic EMV, 02 Mastercard (PayPass), 03 Visa
 *          (PayWave), 04 American Express, 05 Discover. Feeds kernelType.
 *  - DFC10B  Parameter type, which interface the entry applies to. Established by the section
 *          headings below: 00 default, 01 contact, 02 contactless. Feeds paramType.
 *  - DFC10C  Kernel ID. UNCERTAIN how it differs from DFC10A: it equals DFC10A in every entry
 *          except Discover, where DFC10A is 05 and DFC10C is 06. Feeds kernelID.
 *  - DFC10D  Extended selection support flag, from the field name only; 01 in the two entries for
 *          the proprietary AID F045535749544348303031 (8b and 14b) and 00 elsewhere, reason
 *          unknown. Feeds extSelectSupFlg.
 *
 * A tag the tables do NOT carry (not one of the 29 above), recorded because the ZCS adapter has to
 * account for it:
 *  - DF18  In the ZCS EmvApp it is onLinePINFlag (0 = online PIN not supported, 1 = supported).
 *          The ZCS documentation shows DF180101 once, as one element of its single addApp(byte[])
 *          example. The shared tables have no such tag, so the ZCS adapter sets the field from a
 *          constant (1). Where Ciontek's equivalent behaviour comes from is not established:
 *          CS20's CiontekEmvPaymentProcessor sets bypassPin = false in setTerminalParam, a
 *          terminal-wide setting, not a per-AID capability; whether it is the counterpart of
 *          DF18 is a conjecture, nothing here shows it.
 *
 * CAPK entries (every one carries 9F06, 9F22, DF05, DF06, DF07, DF02, DF04 and DF03):
 *  - 9F06  RID of the scheme the key belongs to, 5 bytes. Feeds rid.
 *  - 9F22  Certification Authority Public Key Index, 1 byte. With the RID it names the key. Feeds
 *          index.
 *  - DF05  Expiry date as YYYYMMDD. Feeds expDate. All 26 entries read 20201231, which is in the
 *          past; UNCERTAIN whether the kernel enforces it.
 *  - DF06  Hash algorithm indicator (01 is SHA-1 in EMV). Feeds hashInd.
 *  - DF07  Public key algorithm indicator (01 is RSA in EMV). Feeds arithInd.
 *  - DF02  Modulus. Feeds modul.
 *  - DF04  Public exponent. Feeds exponent.
 *  - DF03  Checksum, 20 bytes, the SHA-1 check value over the key. Feeds checkSum.
 */
object EmvConfiguration {

    /**
     * An entry as tag to hex value. Throws [IllegalArgumentException] if the entry is not
     * well-formed — see [requireWellFormed].
     *
     * Three things to know before relying on the result:
     *  - A tag that repeats keeps its first value, which is how each terminal library's adapter
     *    has always read these entries. A repeat in the tables themselves is a hand-editing slip,
     *    and EmvConfigurationTest rejects it.
     *  - The result is flat. A constructed tag (one whose value is itself TLV) comes back as one
     *    opaque hex value; its children are not parsed out. The tables hold none today.
     *  - Hex is upper-cased on the way through, tags and values alike, because [IccData.parse]
     *    does so. A value written in lower case in the table reads back in upper case: CAPK entry
     *    16 (RID A000000004, index EF) is the one such today.
     */
    fun tlvOf(entry: String): Map<String, String> {
        requireWellFormed(entry)
        val out = LinkedHashMap<String, String>()
        for (tlv in IccData.parse(entry)) out.putIfAbsent(tlv.tag, tlv.value)
        return out
    }

    /**
     * Throws [IllegalArgumentException], saying what was wrong, unless [entry] is non-empty hex of
     * whole bytes that reads as BER-TLV from start to end. Reuses [IccData.parse] for the BER-TLV
     * rules (multi-byte tags such as DF8101, long-form lengths) so there is one reading of them.
     *
     * [IccData.parse] reports only that it failed, not where or why, so the last check names the
     * ways it can fail rather than claiming one: a tag cut off before its last byte, a length
     * missing or cut off, an indefinite length, a length field wider than three bytes, or a
     * length that runs past the end of the entry.
     */
    fun requireWellFormed(entry: String) {
        val where = entry.take(24)
        require(entry.isNotEmpty()) { "empty EMV entry" }
        require(entry.length % 2 == 0) { "odd number of hex digits (${entry.length}) in EMV entry: $where" }
        require(entry.all { it in "0123456789ABCDEFabcdef" }) { "not hex: EMV entry $where" }
        require(IccData.parse(entry).isNotEmpty()) {
            "EMV entry is not well-formed BER-TLV — a tag or length is cut off, a length is " +
                "indefinite or too wide, or a length runs past the end of the entry: $where"
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  CAPK Data — 26 entries from posDemo UpdateDataController
    // ═════════════════════════════════════════════════════════════

    // NOTE: this comment used to claim 27 entries; the table has always held 26. Moving it from
    // CS20 did not drop one — the hex was proven identical to the CS20 original. Whether a 27th
    // CA public key is genuinely missing has to be settled against the acquirer's authoritative
    // CAPK list, because a missing CA key makes offline data authentication fail for every card
    // signed under it. Do not add or change any hex here to chase that number.

    val CAPK_TLV: List<String> = listOf(
        // CAPK 1: AMEX RID A000000025, index 00
        "9F0605A000000025" +
                "9F220100" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281A09C6BE5ADB10B4BE3DCE2099B4B210672B89656EBA091204F613ECC623BEDC9C6D77B660E8BAEEA7F7CE30F1B153879A4E36459343D1FE47ACDBD41FCD710030C2BA1D9461597982C6E1BDD08554B726F5EFF7913CE59E79E357295C321E26D0B8BE270A9442345C753E2AA2ACFC9D30850602FE6CAC00C6DDF6B8D9D9B4879B2826B042A07F0E5AE526A3D3C4D22C72B9EAA52EED8893866F866387AC05A1399" +
                "DF0403000003" +
                "DF0314EC0A59D35D19F031E9E8CBEC56DB80E22B1DE130",

        // CAPK 2: Discover RID A000000672, index 00
        "9F0605A000000672" +
                "9F220100" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281A09C6BE5ADB10B4BE3DCE2099B4B210672B89656EBA091204F613ECC623BEDC9C6D77B660E8BAEEA7F7CE30F1B153879A4E36459343D1FE47ACDBD41FCD710030C2BA1D9461597982C6E1BDD08554B726F5EFF7913CE59E79E357295C321E26D0B8BE270A9442345C753E2AA2ACFC9D30850602FE6CAC00C6DDF6B8D9D9B4879B2826B042A07F0E5AE526A3D3C4D22C72B9EAA52EED8893866F866387AC05A1399" +
                "DF0403000003" +
                "DF0314EC0A59D35D19F031E9E8CBEC56DB80E22B1DE130",

        // CAPK 3: Mastercard RID A000000004, index 02
        "9F0605A000000004" +
                "9F220102" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281C0A99A6D3E071889ED9E3A0C391C69B0B804FC160B2B4BDD570C92DD5A0F45F53E8621F7C96C40224266735E1EE1B3C06238AE35046320FD8E81F8CEB3F8B4C97B940930A3AC5E790086DAD41A6A4F5117BA1CE2438A51AC053EB002AED866D2C458FD73359021A12029A0C043045C11664FE0219EC63C10BF2155BB2784609A106421D45163799738C1C30909BB6C6FE52BBB76397B9740CE064A613FF8411185F08842A423EAD20EDFFBFF1CD6C3FE0C9821479199C26D8572CC8AFFF087A9C3" +
                "DF0403000003" +
                "DF031433408B96C814742AD73536C72F0926E4471E8E47",

        // CAPK 4: Mastercard RID A000000004, index 03
        "9F0605A000000004" +
                "9F220103" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028180C2490747FE17EB0584C88D47B1602704150ADC88C5B998BD59CE043EDEBF0FFEE3093AC7956AD3B6AD4554C6DE19A178D6DA295BE15D5220645E3C8131666FA4BE5B84FE131EA44B039307638B9E74A8C42564F892A64DF1CB15712B736E3374F1BBB6819371602D8970E97B900793C7C2A89A4A1649A59BE680574DD0B60145" +
                "DF0403000003" +
                "DF03145ADDF21D09278661141179CBEFF272EA384B13BB",

        // CAPK 5: Mastercard RID A000000004, index 04
        "9F0605A000000004" +
                "9F220104" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028190A6DA428387A502D7DDFB7A74D3F412BE762627197B25435B7A81716A700157DDD06F7CC99D6CA28C2470527E2C03616B9C59217357C2674F583B3BA5C7DCF2838692D023E3562420B4615C439CA97C44DC9A249CFCE7B3BFB22F68228C3AF13329AA4A613CF8DD853502373D62E49AB256D2BC17120E54AEDCED6D96A4287ACC5C04677D4A5A320DB8BEE2F775E5FEC5" +
                "DF0403000003" +
                "DF0314381A035DA58B482EE2AF75F4C3F2CA469BA4AA6C",

        // CAPK 6: Mastercard RID A000000004, index 05
        "9F0605A000000004" +
                "9F220105" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281B0B8048ABC30C90D976336543E3FD7091C8FE4800DF820ED55E7E94813ED00555B573FECA3D84AF6131A651D66CFF4284FB13B635EDD0EE40176D8BF04B7FD1C7BACF9AC7327DFAA8AA72D10DB3B8E70B2DDD811CB4196525EA386ACC33C0D9D4575916469C4E4F53E8E1C912CC618CB22DDE7C3568E90022E6BBA770202E4522A2DD623D180E215BD1D1507FE3DC90CA310D27B3EFCCD8F83DE3052CAD1E48938C68D095AAC91B5F37E28BB49EC7ED597" +
                "DF0403000003" +
                "DF0314EBFA0D5D06D8CE702DA3EAE890701D45E274C845",

        // CAPK 7: Mastercard RID A000000004, index 06
        "9F0605A000000004" +
                "9F220106" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281F8CB26FC830B43785B2BCE37C81ED334622F9622F4C89AAE641046B2353433883F307FB7C974162DA72F7A4EC75D9D657336865B8D3023D3D645667625C9A07A6B7A137CF0C64198AE38FC238006FB2603F41F4F3BB9DA1347270F2F5D8C606E420958C5F7D50A71DE30142F70DE468889B5E3A08695B938A50FC980393A9CBCE44AD2D64F630BB33AD3F5F5FD495D31F37818C1D94071342E07F1BEC2194F6035BA5DED3936500EB82DFDA6E8AFB655B1EF3D0D7EBF86B66DD9F29F6B1D324FE8B26CE38AB2013DD13F611E7A594D675C4432350EA244CC34F3873CBA06592987A1D7E852ADC22EF5A2EE28132031E48F74037E3B34AB747F" +
                "DF0403000003" +
                "DF0314F910A1504D5FFB793D94F3B500765E1ABCAD72D9",

        // CAPK 8: Mastercard RID A000000004, index F1
        "9F0605A000000004" +
                "9F2201F1" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281B0A0DCF4BDE19C3546B4B6F0414D174DDE294AABBB828C5A834D73AAE27C99B0B053A90278007239B6459FF0BBCD7B4B9C6C50AC02CE91368DA1BD21AAEADBC65347337D89B68F5C99A09D05BE02DD1F8C5BA20E2F13FB2A27C41D3F85CAD5CF6668E75851EC66EDBF98851FD4E42C44C1D59F5984703B27D5B9F21B8FA0D93279FBBF69E090642909C9EA27F898959541AA6757F5F624104F6E1D3A9532F2A6E51515AEAD1B43B3D7835088A2FAFA7BE7" +
                "DF0403000003" +
                "DF0314D8E68DA167AB5A85D8C3D55ECB9B0517A1A5B4BB",

        // CAPK 9: Mastercard RID A000000004, index F3
        "9F0605A000000004" +
                "9F2201F3" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF02819098F0C770F23864C2E766DF02D1E833DFF4FFE92D696E1642F0A88C5694C6479D16DB1537BFE29E4FDC6E6E8AFD1B0EB7EA0124723C333179BF19E93F10658B2F776E829E87DAEDA9C94A8B3382199A350C077977C97AFF08FD11310AC950A72C3CA5002EF513FCCC286E646E3C5387535D509514B3B326E1234F9CB48C36DDD44B416D23654034A66F403BA511C5EFA3" +
                "DF0403000003" +
                "DF0314A69AC7603DAF566E972DEDC2CB433E07E8B01A9A",

        // CAPK 10: Mastercard RID A000000004, index F5
        "9F0605A000000004" +
                "9F2201F5" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281F8A6E6FB72179506F860CCCA8C27F99CECD94C7D4F3191D303BBEE37481C7AA15F233BA755E9E4376345A9A67E7994BDC1C680BB3522D8C93EB0CCC91AD31AD450DA30D337662D19AC03E2B4EF5F6EC18282D491E19767D7B24542DFDEFF6F62185503532069BBB369E3BB9FB19AC6F1C30B97D249EEE764E0BAC97F25C873D973953E5153A42064BBFABFD06A4BB486860BF6637406C9FC36813A4A75F75C31CCA9F69F8DE59ADECEF6BDE7E07800FCBE035D3176AF8473E23E9AA3DFEE221196D1148302677C720CFE2544A03DB553E7F1B8427BA1CC72B0F29B12DFEF4C081D076D353E71880AADFF386352AF0AB7B28ED49E1E672D11F9" +
                "DF0403000003" +
                "DF0314C2239804C8098170BE52D6D5D4159E81CE8466BF",

        // CAPK 11: Mastercard RID A000000004, index F6
        "9F0605A000000004" +
                "9F2201F6" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281E0A25A6BD783A5EF6B8FB6F83055C260F5F99EA16678F3B9053E0F6498E82C3F5D1E8C38F13588017E2B12B3D8FF6F50167F46442910729E9E4D1B3739E5067C0AC7A1F4487E35F675BC16E233315165CB142BFDB25E301A632A54A3371EBAB6572DEEBAF370F337F057EE73B4AE46D1A8BC4DA853EC3CC12C8CBC2DA18322D68530C70B22BDAC351DD36068AE321E11ABF264F4D3569BB71214545005558DE26083C735DB776368172FE8C2F5C85E8B5B890CC682911D2DE71FA626B8817FCCC08922B703869F3BAEAC1459D77CD85376BC36182F4238314D6C4212FBDD7F23D3" +
                "DF0403000003" +
                "DF0314502909ED545E3C8DBD00EA582D0617FEE9F6F684",

        // CAPK 12: Mastercard RID A000000004, index F7
        "9F0605A000000004" +
                "9F2201F7" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF02818094EA62F6D58320E354C022ADDCF0559D8CF206CD92E869564905CE21D720F971B7AEA374830EBE1757115A85E088D41C6B77CF5EC821F30B1D890417BF2FA31E5908DED5FA677F8C7B184AD09028FDDE96B6A6109850AA800175EABCDBBB684A96C2EB6379DFEA08D32FE2331FE103233AD58DCDB1E6E077CB9F24EAEC5C25AF" +
                "DF0403000003" +
                "DF0314EEB0DD9B2477BEE3209A914CDBA94C1C4A9BDED9",

        // CAPK 13: Mastercard RID A000000004, index F8
        "9F0605A000000004" +
                "9F2201F8" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028180A1F5E1C9BD8650BD43AB6EE56B891EF7459C0A24FA84F9127D1A6C79D4930F6DB1852E2510F18B61CD354DB83A356BD190B88AB8DF04284D02A4204A7B6CB7C5551977A9B36379CA3DE1A08E69F301C95CC1C20506959275F41723DD5D2925290579E5A95B0DF6323FC8E9273D6F849198C4996209166D9BFC973C361CC826E1" +
                "DF0403000003" +
                "DF0314F06ECC6D2AAEBF259B7E755A38D9A9B24E2FF3DD",

        // CAPK 14: Mastercard RID A000000004, index F9
        "9F0605A000000004" +
                "9F2201F9" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281C0A99A6D3E071889ED9E3A0C391C69B0B804FC160B2B4BDD570C92DD5A0F45F53E8621F7C96C40224266735E1EE1B3C06238AE35046320FD8E81F8CEB3F8B4C97B940930A3AC5E790086DAD41A6A4F5117BA1CE2438A51AC053EB002AED866D2C458FD73359021A12029A0C043045C11664FE0219EC63C10BF2155BB2784609A106421D45163799738C1C30909BB6C6FE52BBB76397B9740CE064A613FF8411185F08842A423EAD20EDFFBFF1CD6C3FE0C9821479199C26D8572CC8AFFF087A9C3" +
                "DF0403000003" +
                "DF0314336712DCC28554809C6AA9B02358DE6F755164DB",

        // CAPK 15: Mastercard RID A000000004, index FA
        "9F0605A000000004" +
                "9F2201FA" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028190A90FCD55AA2D5D9963E35ED0F440177699832F49C6BAB15CDAE5794BE93F934D4462D5D12762E48C38BA83D8445DEAA74195A301A102B2F114EADA0D180EE5E7A5C73E0C4E11F67A43DDAB5D55683B1474CC0627F44B8D3088A492FFAADAD4F42422D0E7013536C3C49AD3D0FAE96459B0F6B1B6056538A3D6D44640F94467B108867DEC40FAAECD740C00E2B7A8852D" +
                "DF0403000003" +
                "DF03145BED4068D96EA16D2D77E03D6036FC7A160EA99C",

        // CAPK 16: Mastercard RID A000000004, index FE
        "9F0605A000000004" +
                "9F2201FE" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028180A653EAC1C0F786C8724F737F172997D63D1C3251C44402049B865BAE877D0F398CBFBE8A6035E24AFA086BEFDE9351E54B95708EE672F0968BCD50DCE40F783322B2ABA04EF137EF18ABF03C7DBC5813AEAEF3AA7797BA15DF7D5BA1CBAF7FD520B5A482D8D3FEE105077871113E23A49AF3926554A70FE10ED728CF793B62A1" +
                "DF0403000003" +
                "DF03149A295B05FB390EF7923F57618A9FDA2941FC34E0",

        // CAPK 17: Mastercard RID A000000004, index EF
        "9F0605A000000004" +
                "9F2201EF" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281F8a191cb87473f29349b5d60a88b3eaee0973aa6f1a082f358d849fddff9c091f899eda9792caf09ef28f5d22404b88a2293eebbc1949c43bea4d60cfd879a1539544e09e0f09f60f065b2bf2a13ecc705f3d468b9d33ae77ad9d3f19ca40f23dcf5eb7c04dc8f69eba565b1ebcb4686cd274785530ff6f6e9ee43aa43fdb02ce00daec15c7b8fd6a9b394baba419d3f6dc85e16569be8e76989688efea2df22ff7d35c043338deaa982a02b866de5328519ebbcd6f03cdd686673847f84db651ab86c28cf1462562c577b853564a290c8556d818531268d25cc98a4cc6a0bdfffda2dcca3a94c998559e307fddf915006d9a987b07ddaeb3b" +
                "DF0403000003" +
                "DF031421766EBB0EE122AFB65D7845B73DB46BAB65427A",

        // CAPK 18: Visa RID A000000003, index 01
        "9F0605A000000003" +
                "9F220101" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028180C696034213D7D8546984579D1D0F0EA519CFF8DEFFC429354CF3A871A6F7183F1228DA5C7470C055387100CB935A712C4E2864DF5D64BA93FE7E63E71F25B1E5F5298575EBE1C63AA617706917911DC2A75AC28B251C7EF40F2365912490B939BCA2124A30A28F54402C34AECA331AB67E1E79B285DD5771B5D9FF79EA630B75" +
                "DF0403000003" +
                "DF0314D34A6A776011C7E7CE3AEC5F03AD2F8CFC5503CC",

        // CAPK 19: Visa RID A000000003, index 07
        "9F0605A000000003" +
                "9F220107" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028190A89F25A56FA6DA258C8CA8B40427D927B4A1EB4D7EA326BBB12F97DED70AE5E4480FC9C5E8A972177110A1CC318D06D2F8F5C4844AC5FA79A4DC470BB11ED635699C17081B90F1B984F12E92C1C529276D8AF8EC7F28492097D8CD5BECEA16FE4088F6CFAB4A1B42328A1B996F9278B0B7E3311CA5EF856C2F888474B83612A82E4E00D0CD4069A6783140433D50725F" +
                "DF0403000003" +
                "DF0314B4BC56CC4E88324932CBC643D6898F6FE593B172",

        // CAPK 20: Visa RID A000000003, index 08
        "9F0605A000000003" +
                "9F220108" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281B0D9FD6ED75D51D0E30664BD157023EAA1FFA871E4DA65672B863D255E81E137A51DE4F72BCC9E44ACE12127F87E263D3AF9DD9CF35CA4A7B01E907000BA85D24954C2FCA3074825DDD4C0C8F186CB020F683E02F2DEAD3969133F06F7845166ACEB57CA0FC2603445469811D293BFEFBAFAB57631B3DD91E796BF850A25012F1AE38F05AA5C4D6D03B1DC2E568612785938BBC9B3CD3A910C1DA55A5A9218ACE0F7A21287752682F15832A678D6E1ED0B" +
                "DF0403000003" +
                "DF031420D213126955DE205ADC2FD2822BD22DE21CF9A8",

        // CAPK 21: Visa RID A000000003, index 09
        "9F0605A000000003" +
                "9F220109" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281F89D912248DE0A4E39C1A7DDE3F6D2588992C1A4095AFBD1824D1BA74847F2BC4926D2EFD904B4B54954CD189A54C5D1179654F8F9B0D2AB5F0357EB642FEDA95D3912C6576945FAB897E7062CAA44A4AA06B8FE6E3DBA18AF6AE3738E30429EE9BE03427C9D64F695FA8CAB4BFE376853EA34AD1D76BFCAD15908C077FFE6DC5521ECEF5D278A96E26F57359FFAEDA19434B937F1AD999DC5C41EB11935B44C18100E857F431A4A5A6BB65114F174C2D7B59FDF237D6BB1DD0916E644D709DED56481477C75D95CDD68254615F7740EC07F330AC5D67BCD75BF23D28A140826C026DBDE971A37CD3EF9B8DF644AC385010501EFC6509D7A41" +
                "DF0403000003" +
                "DF03141FF80A40173F52D7D27E0F26A146A1C8CCB29046",

        // CAPK 22: Visa RID A000000003, index 99
        "9F0605A000000003" +
                "9F220199" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028180AB79FCC9520896967E776E64444E5DCDD6E13611874F3985722520425295EEA4BD0C2781DE7F31CD3D041F565F747306EED62954B17EDABA3A6C5B85A1DE1BEB9A34141AF38FCF8279C9DEA0D5A6710D08DB4124F041945587E20359BAB47B7575AD94262D4B25F264AF33DEDCF28E09615E937DE32EDC03C54445FE7E382777" +
                "DF0403000003" +
                "DF03144ABFFD6B1C51212D05552E431C5B17007D2F5E6D",

        // CAPK 23: Visa RID A000000003, index 95
        "9F0605A000000003" +
                "9F220195" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF028190BE9E1FA5E9A803852999C4AB432DB28600DCD9DAB76DFAAA47355A0FE37B1508AC6BF38860D3C6C2E5B12A3CAAF2A7005A7241EBAA7771112C74CF9A0634652FBCA0E5980C54A64761EA101A114E0F0B5572ADD57D010B7C9C887E104CA4EE1272DA66D997B9A90B5A6D624AB6C57E73C8F919000EB5F684898EF8C3DBEFB330C62660BED88EA78E909AFF05F6DA627B" +
                "DF0403000003" +
                "DF0314EE1511CEC71020A9B90443B37B1D5F6E703030F6",

        // CAPK 24: Visa RID A000000003, index 92
        "9F0605A000000003" +
                "9F220192" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281B0996AF56F569187D09293C14810450ED8EE3357397B18A2458EFAA92DA3B6DF6514EC060195318FD43BE9B8F0CC669E3F844057CBDDF8BDA191BB64473BC8DC9A730DB8F6B4EDE3924186FFD9B8C7735789C23A36BA0B8AF65372EB57EA5D89E7D14E9C7B6B557460F10885DA16AC923F15AF3758F0F03EBD3C5C2C949CBA306DB44E6A2C076C5F67E281D7EF56785DC4D75945E491F01918800A9E2DC66F60080566CE0DAF8D17EAD46AD8E30A247C9F" +
                "DF0403000003" +
                "DF0314429C954A3859CEF91295F663C963E582ED6EB253",

        // CAPK 25: Visa RID A000000003, index 94
        "9F0605A000000003" +
                "9F220194" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0281F8ACD2B12302EE644F3F835ABD1FC7A6F62CCE48FFEC622AA8EF062BEF6FB8BA8BC68BBF6AB5870EED579BC3973E121303D34841A796D6DCBC41DBF9E52C4609795C0CCF7EE86FA1D5CB041071ED2C51D2202F63F1156C58A92D38BC60BDF424E1776E2BC9648078A03B36FB554375FC53D57C73F5160EA59F3AFC5398EC7B67758D65C9BFF7828B6B82D4BE124A416AB7301914311EA462C19F771F31B3B57336000DFF732D3B83DE07052D730354D297BEC72871DCCF0E193F171ABA27EE464C6A97690943D59BDABB2A27EB71CEEBDAFA1176046478FD62FEC452D5CA393296530AA3F41927ADFE434A2DF2AE3054F8840657A26E0FC617" +
                "DF0403000003" +
                "DF0314C4A3C43CCF87327D136B804160E47D43B60E6E0F",

        // CAPK 26: Visa RID A000000003, index 57
        "9F0605A000000003" +
                "9F220157" +
                "DF050420201231" +
                "DF060101" +
                "DF070101" +
                "DF0260942B7F2BA5EA307312B63DF77C5243618ACC2002BD7ECB74D821FE7BDC78BF28F49F74190AD9B23B9713B140FFEC1FB429D93F56BDC7ADE4AC075D75532C1E590B21874C7952F29B8C0F0C1CE3AEEDC8DA25343123E71DCF86C6998E15F756E3" +
                "DF0403000003" +
                "DF0314251A5F5DE61CF28B5C6E2B5807C0644A01D46FF5"
    )

    // ═════════════════════════════════════════════════════════════
    //  AID Data — 20 entries from posDemo UpdateDataController
    //  Includes contactless (paramType=2), contact (paramType=1),
    //  and default (paramType=0) configurations.
    // ═════════════════════════════════════════════════════════════

    // Common middle section shared by Visa/Discover-style AIDs (version 008C)
    private const val COMMON_VISA_MID =
        "DF010100" +
                "9F0902008C" +
                "DF1105DC4000A800" +
                "DF1205DC4004F800" +
                "DF13050010000000" +
                "DF150400000000" +
                "DF160100" +
                "DF170100" +
                "DF14039F3704" +
                "9F1B0400004000" +
                "9F7B06000000000000" +
                "DF1906000000020000" +
                "DF2006000000200001" +
                "DF2106000000010000" +
                "9F01051234567891" +
                "9F4E0653484F502032" +
                "9F15021342" +
                "9F160F303030303030303030303030303030" +
                "9F3C020056" +
                "9F3D0102" +
                "DFC1080101" +
                "DFC1090101" +
                "DF8101020840" +
                "DF8102039F3704"

    // Common middle section shared by MC/AMEX-style AIDs (version 0002)
    private const val COMMON_MC_MID =
        "DF010100" +
                "9F09020002" +
                "DF1105FC50B8A000" +
                "DF1205FC50808800" +
                "DF13050000000000" +
                "DF150400000000" +
                "DF160199" +
                "DF170199" +
                "DF14039F3704" +
                "9F1B0400004000" +
                "9F7B06000000000000" +
                "DF1906000000020000" +
                "DF2006000000200001" +
                "DF2106000050000000" +
                "9F01051234567891" +
                "9F4E0653484F502032" +
                "9F15021342" +
                "9F160F303030303030303030303030303030" +
                "9F3C020056" +
                "9F3D0102" +
                "DFC1080101" +
                "DFC1090101" +
                "DF8101020840" +
                "DF8102039F3704"

    val AID_TLV: List<String> = listOf(
        // ── Contactless AIDs (paramType=02) ──

        // AID 1: Visa contactless (A000000003) - PayWave kernel
        "9F0605A000000003" + COMMON_VISA_MID +
                "DFC10A0103" + "DFC10B0102" + "DFC10C0103" + "DFC10D0100",

        // AID 2: Discover contactless (A000000672) - Discover kernel
        "9F0605A000000672" + COMMON_VISA_MID +
                "DFC10A0105" + "DFC10B0102" + "DFC10C0106" + "DFC10D0100",

        // AID 3: AMEX contactless (A000000025010801) - AE kernel
        "9F0608A000000025010801" + COMMON_MC_MID +
                "DFC10A0104" + "DFC10B0102" + "DFC10C0104" + "DFC10D0100",

        // AID 4: Mastercard contactless (A0000000041010) - PayPass kernel
        "9F0607A0000000041010" + COMMON_MC_MID +
                "DFC10A0102" + "DFC10B0102" + "DFC10C0102" + "DFC10D0100",

        // AID 5: Visa contactless variant 1 (A000000003101001)
        "9F0608A000000003101001" + COMMON_VISA_MID +
                "DFC10A0103" + "DFC10B0102" + "DFC10C0103" + "DFC10D0100",

        // AID 6: Visa contactless variant 2 (A000000003101002)
        "9F0608A000000003101002" + COMMON_VISA_MID +
                "DFC10A0103" + "DFC10B0102" + "DFC10C0103" + "DFC10D0100",

        // AID 7: MC test contactless (A0000000049999D71601) - PayPass kernel
        "9F060AA0000000049999D71601" + COMMON_MC_MID +
                "DFC10A0102" + "DFC10B0102" + "DFC10C0102" + "DFC10D0100",

        // AID 8: PayWave contactless (A0000007790000)
        "9F0607A0000007790000" + COMMON_MC_MID +
                "DFC10A0103" + "DFC10B0102" + "DFC10C0103" + "DFC10D0100",

        // AID 8b: Proprietary contactless (F045535749544348303031) - PayPass kernel
        "9F060BF045535749544348303031" + COMMON_MC_MID +
                "DFC10A0102" + "DFC10B0102" + "DFC10C0102" + "DFC10D0101",

        // AID 8c: GovPay Tap contactless (F716669034) — BIN 669034 - PayWave kernel
        //
        // Five bytes, so the whole AID is the RID and the entry matches on the RID alone
        // (DF01 00 = PART_MATCH per the vendor's tag doc). Whatever PIX the issuer
        // personalises onto a card, the tap still selects it — GovPay Tap uses ...0001.
        //
        // PayWave, not PayPass: the GovPay HCE presents the Visa qVSDC data model — AIP 0080,
        // a PDOL asking for 9F66 (TTQ), and 9F6C (CTQ) in the GPO response. Driving it with
        // the Mastercard kernel would select the application and then talk past it.
        "9F0605F716669034" + COMMON_VISA_MID +
                "DFC10A0103" + "DFC10B0102" + "DFC10C0103" + "DFC10D0100",

        // ── Contact AIDs (paramType=01) ──

        // AID 9: Visa contact (A000000003) - PayWave kernel
        "9F0605A000000003" + COMMON_VISA_MID +
                "DFC10A0103" + "DFC10B0101" + "DFC10C0103" + "DFC10D0100",

        // AID 10: Mastercard contact (A0000000041010) - PayPass kernel
        "9F0607A0000000041010" + COMMON_MC_MID +
                "DFC10A0102" + "DFC10B0101" + "DFC10C0102" + "DFC10D0100",

        // AID 11: Discover contact (A0000001523010) - Discover kernel
        "9F0607A0000001523010" + COMMON_VISA_MID +
                "DFC10A0105" + "DFC10B0101" + "DFC10C0106" + "DFC10D0100",

        // AID 12: Discover contact (A000000672) - Discover kernel
        "9F0605A000000672" + COMMON_VISA_MID +
                "DFC10A0105" + "DFC10B0101" + "DFC10C0106" + "DFC10D0100",

        // AID 13: AMEX contact (A000000025010801) - AE kernel
        "9F0608A000000025010801" + COMMON_MC_MID +
                "DFC10A0104" + "DFC10B0101" + "DFC10C0104" + "DFC10D0100",

        // AID 14: PayWave contact (A0000007790000)
        "9F0607A0000007790000" + COMMON_MC_MID +
                "DFC10A0103" + "DFC10B0101" + "DFC10C0103" + "DFC10D0100",

        // AID 14b: Proprietary contact (F045535749544348303031) - Generic EMV kernel
        "9F060BF045535749544348303031" +
                "DF010101" +                        // selFlag = exact match (01; this comment once said partial)
                "9F0902008C" +
                "DF1105DC4000A800" +
                "DF1205DC4004F800" +
                "DF13050010000000" +
                "DF150400000000" +
                "DF160100" +
                "DF170100" +
                "DF14039F3704" +
                "9F1B0400004000" +
                "9F7B06000000000000" +
                "DF1906000000020000" +
                "DF2006000000200001" +
                "DF2106000000010000" +
                "9F01051234567891" +
                "9F4E0653484F502032" +
                "9F15021342" +
                "9F160F303030303030303030303030303030" +
                "9F3C020056" +
                "9F3D0102" +
                "DFC1080101" +
                "DFC1090101" +
                "DF8101020840" +
                "DF8102039F3704" +
                "DFC10A0101" + "DFC10B0101" + "DFC10C0101" + "DFC10D0101",

        // ── Default AIDs (paramType=00) ──

        // AID 15: AMEX default (A000000025010801) - AE kernel
        "9F0608A000000025010801" + COMMON_MC_MID +
                "DFC10A0104" + "DFC10B0100" + "DFC10C0104" + "DFC10D0100",

        // AID 16: AMEX default (A000000025010402) - AE kernel
        "9F0608A000000025010402" + COMMON_MC_MID +
                "DFC10A0104" + "DFC10B0100" + "DFC10C0104" + "DFC10D0100",

        // AID 17: AMEX default (A000000025010403) - AE kernel
        "9F0608A000000025010403" + COMMON_MC_MID +
                "DFC10A0104" + "DFC10B0100" + "DFC10C0104" + "DFC10D0100"
    )
}
