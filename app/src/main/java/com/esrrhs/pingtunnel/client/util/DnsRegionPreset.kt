package com.esrrhs.pingtunnel.client.util
import android.content.Context
import androidx.annotation.StringRes
import com.esrrhs.pingtunnel.client.R

/**
 * gohome 内置国家/地区 DNS 与直连分流预设在 Android 端的镜像定义。
 */
enum class DnsRegionPreset(
    val code: String,
    @StringRes val titleRes: Int,
    val directTlds: List<String> = emptyList(),
    val directDomains: List<String> = emptyList(),
) {
    CN(
        code = "CN",
        titleRes = R.string.region_preset_cn,
        directTlds = listOf("cn", "xn--fiqs8s", "xn--55qx5d", "xn--io0a7i"),
        directDomains = emptyList(),
    ),
    IR(
        code = "IR",
        titleRes = R.string.region_preset_ir,
        directTlds = listOf("ir", "xn--mgba3a4f16a"),
        directDomains = listOf("shaparak.ir", "bmi.ir", "bankmellat.ir", "mellatbank.com", "tejaratbank.ir", "bpi.ir", "samanepay.com", "parsian-bank.ir", "enbank.ir", "sadadpsp.ir", "snapp.ir", "snapp.market", "snappfood.ir", "snapptrip.com", "tapsi.ir", "digikala.com", "dkstatics-public.digikala.com", "divar.ir", "torob.com", "emalls.ir", "basalam.com", "sheypoor.com", "aparat.com", "telewebion.com", "filimo.com", "namava.ir", "bale.ai", "rubika.ir", "eitaa.com", "gap.im", "soroush-app.ir", "gov.ir", "moi.ir", "mci.ir", "irancell.ir", "rightel.ir", "tci.ir"),
    ),
    RU(
        code = "RU",
        titleRes = R.string.region_preset_ru,
        directTlds = listOf("ru", "su", "xn--p1ai", "xn--e1a4c", "xn--c1avg"),
        directDomains = listOf("yandex.ru", "yandex.com", "ya.ru", "mail.ru", "dzen.ru", "rambler.ru", "vk.com", "vk.ru", "ok.ru", "rutube.ru", "kinopoisk.ru", "smotrim.ru", "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "kremlin.ru", "cbr.ru", "pfr.gov.ru", "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru", "vtb.ru", "alfabank.ru", "nspk.ru", "mir-plat.ru", "gazprombank.ru", "open.ru", "ozon.ru", "wildberries.ru", "avito.ru", "market.yandex.ru", "cdek.ru", "pochta.ru"),
    ),
    TR(
        code = "TR",
        titleRes = R.string.region_preset_tr,
        directTlds = listOf("tr"),
        directDomains = listOf("turkiye.gov.tr", "meb.gov.tr", "saglik.gov.tr", "gib.gov.tr", "egm.gov.tr", "ziraatbank.com.tr", "isbank.com.tr", "garantibbva.com.tr", "akbank.com", "yapikredi.com.tr", "halkbank.com.tr", "vakifbank.com.tr", "papara.com", "hepsiburada.com", "trendyol.com", "sahibinden.com", "n11.com", "getir.com", "yemeksepeti.com", "turkcell.com.tr", "vodafone.com.tr", "turktelekom.com.tr", "ensonhaber.com", "hurriyet.com.tr"),
    ),
    MM(
        code = "MM",
        titleRes = R.string.region_preset_mm,
        directTlds = listOf("mm"),
        directDomains = listOf("kbzbank.com", "kbzpay.com", "ayabank.com", "cbbank.com.mm", "wavemoney.com.mm", "mabbank.com", "mpt.com.mm", "mytel.com.mm", "atom.com.mm", "shop.com.mm", "president-office.gov.mm", "myanmar.gov.mm"),
    ),
    PK(
        code = "PK",
        titleRes = R.string.region_preset_pk,
        directTlds = listOf("pk", "xn--mgbai9azgqp6j"),
        directDomains = listOf("pakistan.gov.pk", "nadra.gov.pk", "fbr.gov.pk", "pass.gov.pk", "hbl.com", "ubldigital.com", "mcb.com.pk", "easypaisa.com.pk", "jazzcash.com.pk", "meezanbank.com", "daraz.pk", "ptcl.com.pk", "jazz.com.pk", "telenor.com.pk", "zong.com.pk"),
    ),
    VN(
        code = "VN",
        titleRes = R.string.region_preset_vn,
        directTlds = listOf("vn"),
        directDomains = listOf("zalo.me", "vng.com.vn", "fpt.com.vn", "viettel.vn", "vnpt.com.vn", "vietcombank.com.vn", "techcombank.com.vn", "momo.vn", "vnpay.vn", "bidv.com.vn", "vietinbank.vn", "shopee.vn", "tiki.vn", "lazada.vn", "vnexpress.net", "dantri.com.vn", "chotot.com"),
    ),
    EG(
        code = "EG",
        titleRes = R.string.region_preset_eg,
        directTlds = listOf("eg", "xn--wgbh1c"),
        directDomains = listOf("digital.gov.eg", "egypt.gov.eg", "mohp.gov.eg", "fawry.com", "cibeg.com", "nbe.com.eg", "banquemisr.com", "qnb.com.eg", "te.eg", "vodafone.com.eg", "orange.eg", "etisalat.eg", "jumia.com.eg", "amazon.eg"),
    ),
    AE(
        code = "AE",
        titleRes = R.string.region_preset_ae,
        directTlds = listOf("ae", "xn--mgbaam7a8h"),
        directDomains = listOf("u.ae", "dubai.ae", "icp.gov.ae", "mohre.gov.ae", "rta.ae", "enbd.com", "adcb.com", "fab.com", "mashreqbank.com", "alansari.com", "careem.com", "noon.com", "talabat.com", "etisalat.ae", "du.ae"),
    ),
    SA(
        code = "SA",
        titleRes = R.string.region_preset_sa,
        directTlds = listOf("sa", "xn--mgberp4a5d4ar"),
        directDomains = listOf("absher.sa", "my.gov.sa", "qiwa.sa", "zatca.gov.sa", "sehhaty.sa", "alrajhibank.com.sa", "alahli.com", "riyadbank.com", "alinma.com", "stcpay.com.sa", "urpay.com.sa", "stc.com.sa", "mobily.com.sa", "noon.com", "hungerstation.com", "jahez.net", "haraj.com.sa"),
    ),
    IN(
        code = "IN",
        titleRes = R.string.region_preset_in,
        directTlds = listOf("in", "xn--h2brj9c", "xn--2scrj9c", "xn--45brj9c"),
        directDomains = listOf("uidai.gov.in", "incometax.gov.in", "irctc.co.in", "passportindia.gov.in", "digilocker.gov.in", "india.gov.in", "sbi.co.in", "hdfcbank.com", "icicibank.com", "axisbank.com", "paytm.com", "phonepe.com", "npci.org.in", "jio.com", "airtel.in", "flipkart.com", "hotstar.com"),
    ),
    NONE(
        code = "NONE",
        titleRes = R.string.region_preset_none,
        directTlds = emptyList(),
        directDomains = emptyList(),
    ),
;

    /**
     * 获取该国家预设的直连域名全量集合（TLD + 核心域名，CN 额外合并 assets 中的 domain_direct_cn.txt）。
     */
    fun resolveDirectDomains(context: Context): Set<String> {
        val result = LinkedHashSet<String>()
        result.addAll(directTlds)
        result.addAll(directDomains)
        if (this == CN) {
            result.addAll(BundledDirectDomains.load(context))
        }
        return result
    }

    companion object {
        fun fromCode(code: String?): DnsRegionPreset {
            val c = code?.trim()?.uppercase() ?: "CN"
            return entries.firstOrNull { it.code == c } ?: CN
        }
    }
}
