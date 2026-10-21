{#
 # ETPH (Errant Solutions): the phone as an uplink. MIT licence.
 #}
<script>
    $( document ).ready(function() {
        mapDataToFormUI({'frm_general': "/api/etph/settings/get"}).done(function() {
            formatTokenizersUI();
            $('.selectpicker').selectpicker('refresh');
            updateServiceControlUI('etph');
        });
        function phone() {
            ajaxGet("/api/etph/service/phone", {}, function(s) {
                $("#etph_state").text(s.state || "stopped");
                $("#etph_device").text(s.device || "-");
                $("#etph_pro").text(s.phone_pro === true ? "yes" : (s.phone_pro === false ? "no (routers need ETPH Pro)" : "-"));
                $("#etph_traffic").text(((s.bytes_received || 0) / 1e6).toFixed(1) + " MB in / " + ((s.bytes_sent || 0) / 1e6).toFixed(1) + " MB out");
                $("#etph_error").text(s.last_error || "");
            });
        }
        phone();
        setInterval(phone, 5000);
        $("#saveAct").click(function() {
            saveFormToEndpoint("/api/etph/settings/set", 'frm_general', function() {
                $("#saveAct_progress").addClass("fa fa-spinner fa-pulse");
                ajaxCall("/api/etph/service/reconfigure", {}, function() {
                    $("#saveAct_progress").removeClass("fa fa-spinner fa-pulse");
                    updateServiceControlUI('etph');
                    phone();
                });
            });
        });
    });
</script>

<div class="content-box">
    <table class="table table-striped opnsense_standard_table_form">
        <thead><tr><th colspan="2">{{ lang._('Phone') }}</th></tr></thead>
        <tbody>
            <tr><td style="width:22%">{{ lang._('State') }}</td><td id="etph_state">-</td></tr>
            <tr><td>{{ lang._('Phone') }}</td><td id="etph_device">-</td></tr>
            <tr><td>{{ lang._('ETPH Pro on the phone') }}</td><td id="etph_pro">-</td></tr>
            <tr><td>{{ lang._('Traffic') }}</td><td id="etph_traffic">-</td></tr>
            <tr><td>{{ lang._('Last error') }}</td><td id="etph_error"></td></tr>
        </tbody>
    </table>
</div>
<div class="content-box" style="padding-bottom: 1.5em;">
    {{ partial("layout_partials/base_form",['fields':generalForm,'id':'frm_general'])}}
    <div class="col-md-12">
        <hr/>
        <button class="btn btn-primary" id="saveAct" type="button"><b>{{ lang._('Apply') }}</b> <i id="saveAct_progress"></i></button>
    </div>
</div>
